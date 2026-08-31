package service;

import dao.ArquivoSequencial;
import model.Carro;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Ordenação externa por ID usando intercalação balanceada.
 *
 * Os runs iniciais podem ser gerados por blocos ordenados em memória ou por
 * seleção por substituição. Nas duas estratégias, os runs são distribuídos em
 * round-robin pelos caminhos e intercalados em grupos limitados pela quantidade
 * de caminhos. O arquivo inteiro nunca é carregado em memória.
 */
public class OrdenacaoExterna {

    /** Intercalação balanceada comum, com runs de até N registros. */
    public ResultadoOrdenacao ordenar(Path arquivoPrincipal, Path diretorioTemp,
                                      int numeroCaminhos, int maxRegistrosMemoria) throws IOException {
        return ordenarInternamente(arquivoPrincipal, diretorioTemp, numeroCaminhos,
                maxRegistrosMemoria, false);
    }

    /** Intercalação balanceada com runs gerados por seleção por substituição. */
    public ResultadoOrdenacao ordenarComSelecaoPorSubstituicao(
            Path arquivoPrincipal, Path diretorioTemp,
            int numeroCaminhos, int tamanhoMemoria) throws IOException {
        return ordenarInternamente(arquivoPrincipal, diretorioTemp, numeroCaminhos,
                tamanhoMemoria, true);
    }

    private ResultadoOrdenacao ordenarInternamente(
            Path arquivoPrincipal, Path diretorioTemp, int numeroCaminhos,
            int limiteMemoria, boolean usarSelecao) throws IOException {
        validarParametros(arquivoPrincipal, numeroCaminhos, limiteMemoria);
        int ultimoId = new ArquivoSequencial(arquivoPrincipal).getUltimoId();

        limparDiretorio(diretorioTemp);
        Files.createDirectories(diretorioTemp);
        Path distribuicaoDir = diretorioTemp.resolve("distribuicao");
        ResultadoDistribuicao distribuicao = usarSelecao
                ? distribuirPorSelecao(arquivoPrincipal, distribuicaoDir, numeroCaminhos, limiteMemoria)
                : distribuirPorBlocos(arquivoPrincipal, distribuicaoDir, numeroCaminhos, limiteMemoria);

        List<Path> runs = distribuicao.runs;
        int runsIniciais = runs.size();
        int passagens = 0;

        if (runs.isEmpty()) {
            Path ordenado = arquivoPrincipal.resolveSibling("dados_ordenados.db");
            new ArquivoSequencial(ordenado).reinicializar(ultimoId);
            substituirArquivoPrincipal(arquivoPrincipal, ordenado);
            limparDiretorio(diretorioTemp);
            return new ResultadoOrdenacao(0, 0, 0,
                    distribuicao.registrosCongelados, true);
        }

        // A saída de cada passagem torna-se a entrada da passagem seguinte.
        while (runs.size() > 1) {
            passagens++;
            Path pastaPassagem = diretorioTemp.resolve("passagem_" + passagens);
            Files.createDirectories(pastaPassagem);
            List<Path> novosRuns = new ArrayList<>();
            for (int i = 0, grupo = 0; i < runs.size(); i += numeroCaminhos, grupo++) {
                int fim = Math.min(i + numeroCaminhos, runs.size());
                List<Path> lote = new ArrayList<>(runs.subList(i, fim));
                Path saida = pastaPassagem.resolve(String.format("merge_%04d.run", grupo));
                intercalarManualmente(lote, saida);
                novosRuns.add(saida);
            }
            for (Path run : runs) Files.deleteIfExists(run);
            runs = novosRuns;
        }

        Path runFinal = runs.get(0);
        int registrosOrdenados = contarRun(runFinal);
        Path arquivoOrdenado = arquivoPrincipal.resolveSibling("dados_ordenados.db");
        escreverArquivoFinal(runFinal, arquivoOrdenado, ultimoId);
        Files.deleteIfExists(runFinal);
        substituirArquivoPrincipal(arquivoPrincipal, arquivoOrdenado);
        limparDiretorio(diretorioTemp);

        return new ResultadoOrdenacao(registrosOrdenados, runsIniciais, passagens,
                distribuicao.registrosCongelados, distribuicao.runsOrdenados);
    }

    private void validarParametros(Path arquivoPrincipal, int caminhos,
                                   int limiteMemoria) throws IOException {
        if (caminhos < 2) throw new IllegalArgumentException("Número de caminhos deve ser >= 2.");
        if (limiteMemoria < 1) throw new IllegalArgumentException("Limite de memória deve ser >= 1.");
        if (!Files.exists(arquivoPrincipal) || Files.size(arquivoPrincipal) < Integer.BYTES) {
            throw new IOException("Arquivo principal inexistente ou inválido.");
        }
    }

    /** Distribuição comum com insertion sort manual em blocos de até N registros. */
    private ResultadoDistribuicao distribuirPorBlocos(
            Path arquivoPrincipal, Path pasta, int caminhos, int limiteMemoria) throws IOException {
        prepararCaminhos(pasta, caminhos);
        List<Path> runs = new ArrayList<>();
        int processados = 0;
        try (RandomAccessFile raf = new RandomAccessFile(arquivoPrincipal.toFile(), "r")) {
            raf.seek(Integer.BYTES);
            int indiceRun = 0;
            while (raf.getFilePointer() < raf.length()) {
                List<Carro> bloco = new ArrayList<>(limiteMemoria);
                while (bloco.size() < limiteMemoria) {
                    Carro proximo = lerProximoAtivo(raf);
                    if (proximo == null) break;
                    bloco.add(proximo);
                }
                if (!bloco.isEmpty()) {
                    ordenarBlocoPorInsercao(bloco);
                    Path run = caminhoDoRun(pasta, caminhos, indiceRun);
                    escreverRun(run, bloco);
                    runs.add(run);
                    processados += bloco.size();
                    indiceRun++;
                }
            }
        }
        return new ResultadoDistribuicao(runs, processados, 0, true);
    }

    /** Insertion sort crescente por ID, sem rotinas prontas de ordenação. */
    private void ordenarBlocoPorInsercao(List<Carro> bloco) {
        for (int i = 1; i < bloco.size(); i++) {
            Carro atual = bloco.get(i);
            int j = i - 1;
            while (j >= 0 && bloco.get(j).getId() > atual.getId()) {
                bloco.set(j + 1, bloco.get(j));
                j--;
            }
            bloco.set(j + 1, atual);
        }
    }

    /**
     * Seleção por substituição. A memória contém no máximo M registros. Itens
     * menores que o último ID escrito ficam congelados para o próximo run.
     */
    private ResultadoDistribuicao distribuirPorSelecao(
            Path arquivoPrincipal, Path pasta, int caminhos, int limiteMemoria) throws IOException {
        prepararCaminhos(pasta, caminhos);
        List<Path> runs = new ArrayList<>();
        List<ItemMemoria> memoria = new ArrayList<>(limiteMemoria);
        int processados = 0;
        int congelados = 0;
        boolean runsOrdenados = true;

        try (RandomAccessFile raf = new RandomAccessFile(arquivoPrincipal.toFile(), "r")) {
            raf.seek(Integer.BYTES);
            while (memoria.size() < limiteMemoria) {
                Carro proximo = lerProximoAtivo(raf);
                if (proximo == null) break;
                memoria.add(new ItemMemoria(proximo, false));
            }

            int indiceRun = 0;
            while (!memoria.isEmpty()) {
                Path run = caminhoDoRun(pasta, caminhos, indiceRun);
                Files.createDirectories(run.getParent());
                int ultimoId = Integer.MIN_VALUE;
                boolean escreveu = false;

                try (DataOutputStream out = new DataOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(run)))) {
                    while (true) {
                        int indiceMenor = encontrarMenorNaoCongelado(memoria);
                        if (indiceMenor < 0) break;
                        ItemMemoria selecionado = memoria.get(indiceMenor);
                        if (selecionado.carro.getId() < ultimoId) runsOrdenados = false;
                        escreverCarroRun(out, selecionado.carro);
                        ultimoId = selecionado.carro.getId();
                        processados++;
                        escreveu = true;

                        Carro novo = lerProximoAtivo(raf);
                        if (novo == null) {
                            memoria.remove(indiceMenor);
                        } else {
                            boolean congelado = novo.getId() < ultimoId;
                            if (congelado) congelados++;
                            memoria.set(indiceMenor, new ItemMemoria(novo, congelado));
                        }
                    }
                }

                if (escreveu) {
                    runs.add(run);
                    indiceRun++;
                } else {
                    Files.deleteIfExists(run);
                }
                for (ItemMemoria item : memoria) item.congelado = false;
            }
        }
        return new ResultadoDistribuicao(runs, processados, congelados, runsOrdenados);
    }

    /** Escolha manual do menor item elegível em memória. */
    private int encontrarMenorNaoCongelado(List<ItemMemoria> memoria) {
        int menor = -1;
        for (int i = 0; i < memoria.size(); i++) {
            ItemMemoria item = memoria.get(i);
            if (item.congelado) continue;
            if (menor < 0 || item.carro.getId() < memoria.get(menor).carro.getId()) menor = i;
        }
        return menor;
    }

    /** Intercala até K runs mantendo somente uma cabeça de cada caminho. */
    private void intercalarManualmente(List<Path> entradas, Path saida) throws IOException {
        List<DataInputStream> streams = new ArrayList<>();
        List<Carro> cabecas = new ArrayList<>();
        try {
            for (Path entrada : entradas) {
                DataInputStream in = new DataInputStream(
                        new BufferedInputStream(Files.newInputStream(entrada)));
                streams.add(in);
                cabecas.add(lerProximoRun(in));
            }
            Files.createDirectories(saida.getParent());
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(saida)))) {
                while (true) {
                    int menor = encontrarMenorCabeca(cabecas);
                    if (menor < 0) break;
                    escreverCarroRun(out, cabecas.get(menor));
                    cabecas.set(menor, lerProximoRun(streams.get(menor)));
                }
            }
        } finally {
            for (DataInputStream in : streams) {
                try { in.close(); } catch (IOException ignored) { }
            }
        }
    }

    /** Escolha manual do menor registro corrente entre os caminhos. */
    private int encontrarMenorCabeca(List<Carro> cabecas) {
        int menor = -1;
        for (int i = 0; i < cabecas.size(); i++) {
            Carro carro = cabecas.get(i);
            if (carro == null) continue;
            if (menor < 0 || carro.getId() < cabecas.get(menor).getId()) menor = i;
        }
        return menor;
    }

    /** Lê o próximo registro ativo sem materializar o restante do arquivo. */
    private Carro lerProximoAtivo(RandomAccessFile raf) throws IOException {
        while (raf.getFilePointer() < raf.length()) {
            byte lapide;
            int tamanho;
            try {
                lapide = raf.readByte();
                tamanho = raf.readInt();
            } catch (EOFException e) {
                throw new EOFException("EOF inesperado durante a distribuição.");
            }
            long restantes = raf.length() - raf.getFilePointer();
            if (tamanho < 0 || tamanho > restantes) {
                throw new IOException("Registro corrompido durante a distribuição.");
            }
            byte[] dados = new byte[tamanho];
            raf.readFully(dados);
            if (lapide == ArquivoSequencial.ATIVO) {
                Carro carro = new Carro();
                carro.fromByteArray(dados);
                return carro;
            }
        }
        return null;
    }

    private void prepararCaminhos(Path pasta, int caminhos) throws IOException {
        for (int i = 0; i < caminhos; i++) {
            Files.createDirectories(pasta.resolve("caminho_" + i));
        }
    }

    private Path caminhoDoRun(Path pasta, int caminhos, int indiceRun) {
        return pasta.resolve("caminho_" + (indiceRun % caminhos))
                .resolve(String.format("run_%04d.run", indiceRun));
    }

    private void escreverRun(Path run, List<Carro> carros) throws IOException {
        Files.createDirectories(run.getParent());
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(run)))) {
            for (Carro carro : carros) escreverCarroRun(out, carro);
        }
    }

    // Formato temporário: [int tamanho][bytes carro]. Runs não usam lápide.
    private void escreverCarroRun(DataOutputStream out, Carro carro) throws IOException {
        byte[] dados = carro.toByteArray();
        out.writeInt(dados.length);
        out.write(dados);
    }

    private Carro lerProximoRun(DataInputStream in) throws IOException {
        int primeiroByte = in.read();
        if (primeiroByte < 0) return null;
        int tamanho = (primeiroByte << 24)
                | (in.readUnsignedByte() << 16)
                | (in.readUnsignedByte() << 8)
                | in.readUnsignedByte();
        if (tamanho < 0 || tamanho > 10_000_000) {
            throw new IOException("Tamanho inválido em run temporário.");
        }
        byte[] dados = new byte[tamanho];
        in.readFully(dados);
        Carro carro = new Carro();
        carro.fromByteArray(dados);
        return carro;
    }

    private int contarRun(Path run) throws IOException {
        int quantidade = 0;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(run)))) {
            while (lerProximoRun(in) != null) quantidade++;
        }
        return quantidade;
    }

    private void escreverArquivoFinal(Path run, Path destino, int ultimoId) throws IOException {
        ArquivoSequencial novo = new ArquivoSequencial(destino);
        novo.reinicializar(ultimoId);
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(run)))) {
            Carro carro;
            while ((carro = lerProximoRun(in)) != null) novo.appendComId(carro);
        }
    }

    /** Substitui dados.db somente depois que o arquivo ordenado está completo. */
    private void substituirArquivoPrincipal(Path principal, Path ordenado) throws IOException {
        String timestamp = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        Path backup = principal.resolveSibling("dados_pre_ordenacao_" + timestamp + ".bak");
        Files.move(principal, backup, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(ordenado, principal, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(backup, principal, StandardCopyOption.REPLACE_EXISTING);
            throw e;
        }
    }

    /** Limpa temporários recursivamente sem usar ordenação pronta. */
    private void limparDiretorio(Path diretorio) throws IOException {
        if (!Files.exists(diretorio)) return;
        if (Files.isDirectory(diretorio)) {
            try (DirectoryStream<Path> filhos = Files.newDirectoryStream(diretorio)) {
                for (Path filho : filhos) limparDiretorio(filho);
            }
        }
        Files.deleteIfExists(diretorio);
    }

    private static class ItemMemoria {
        final Carro carro;
        boolean congelado;
        ItemMemoria(Carro carro, boolean congelado) {
            this.carro = carro;
            this.congelado = congelado;
        }
    }

    private static class ResultadoDistribuicao {
        final List<Path> runs;
        final int registrosProcessados;
        final int registrosCongelados;
        final boolean runsOrdenados;
        ResultadoDistribuicao(List<Path> runs, int registrosProcessados,
                              int registrosCongelados, boolean runsOrdenados) {
            this.runs = runs;
            this.registrosProcessados = registrosProcessados;
            this.registrosCongelados = registrosCongelados;
            this.runsOrdenados = runsOrdenados;
        }
    }

    public static class ResultadoOrdenacao {
        public final int registrosOrdenados;
        public final int runsIniciais;
        public final int passagensIntercalacao;
        public final int registrosCongelados;
        public final boolean runsIniciaisOrdenados;

        public ResultadoOrdenacao(int registrosOrdenados, int runsIniciais,
                                  int passagensIntercalacao, int registrosCongelados,
                                  boolean runsIniciaisOrdenados) {
            this.registrosOrdenados = registrosOrdenados;
            this.runsIniciais = runsIniciais;
            this.passagensIntercalacao = passagensIntercalacao;
            this.registrosCongelados = registrosCongelados;
            this.runsIniciaisOrdenados = runsIniciaisOrdenados;
        }

        @Override
        public String toString() {
            return "registros=" + registrosOrdenados
                    + ", runs iniciais=" + runsIniciais
                    + ", passagens de intercalação=" + passagensIntercalacao;
        }
    }
}
