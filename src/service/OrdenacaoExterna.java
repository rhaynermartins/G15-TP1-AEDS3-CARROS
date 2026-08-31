package service;

import dao.ArquivoSequencial;
import model.Carro;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Ordenação externa por ID.
 *
 * Estratégia:
 * 1) Distribuição: lê no máximo N registros ativos, ordena esse bloco em memória
 *    e cria um arquivo de corrida (run). Os runs são distribuídos em round-robin
 *    entre o número de caminhos informado.
 * 2) Intercalação: em cada passagem, no máximo "caminhos" runs são abertos de uma vez.
 *    Uma PriorityQueue mantém apenas o menor registro corrente de cada run.
 * 3) O run final é convertido para o mesmo formato físico do arquivo principal,
 *    com lápide 0 em todos os registros. Assim, versões antigas e excluídas somem.
 */
public class OrdenacaoExterna {

    public ResultadoOrdenacao ordenar(Path arquivoPrincipal, Path diretorioTemp,
                                      int numeroCaminhos, int maxRegistrosMemoria) throws IOException {
        if (numeroCaminhos < 2) throw new IllegalArgumentException("Número de caminhos deve ser >= 2.");
        if (maxRegistrosMemoria < 1) throw new IllegalArgumentException("Máximo de registros em memória deve ser >= 1.");
        if (!Files.exists(arquivoPrincipal) || Files.size(arquivoPrincipal) < Integer.BYTES) {
            throw new IOException("Arquivo principal inexistente ou inválido.");
        }

        ArquivoSequencial arquivo = new ArquivoSequencial(arquivoPrincipal);
        int ultimoId = arquivo.getUltimoId();

        limparDiretorio(diretorioTemp);
        Files.createDirectories(diretorioTemp);

        List<Path> runs = distribuir(arquivoPrincipal, diretorioTemp.resolve("distribuicao"),
                numeroCaminhos, maxRegistrosMemoria);
        int runsIniciais = runs.size();
        int passagens = 0;

        if (runs.isEmpty()) {
            Path ordenado = arquivoPrincipal.resolveSibling("dados_ordenados.db");
            new ArquivoSequencial(ordenado).reinicializar(ultimoId);
            substituirArquivoPrincipal(arquivoPrincipal, ordenado);
            limparDiretorio(diretorioTemp);
            return new ResultadoOrdenacao(0, 0, 0);
        }

        while (runs.size() > 1) {
            passagens++;
            Path pastaPassagem = diretorioTemp.resolve("passagem_" + passagens);
            Files.createDirectories(pastaPassagem);
            List<Path> novosRuns = new ArrayList<>();

            for (int i = 0, grupo = 0; i < runs.size(); i += numeroCaminhos, grupo++) {
                int fim = Math.min(i + numeroCaminhos, runs.size());
                List<Path> lote = new ArrayList<>(runs.subList(i, fim));
                Path saida = pastaPassagem.resolve(String.format("merge_%04d.run", grupo));
                intercalar(lote, saida);
                novosRuns.add(saida);
            }

            // Runs da passagem anterior já não são necessários.
            for (Path p : runs) Files.deleteIfExists(p);
            runs = novosRuns;
        }

        Path arquivoOrdenado = arquivoPrincipal.resolveSibling("dados_ordenados.db");
        escreverArquivoFinal(runs.get(0), arquivoOrdenado, ultimoId);
        int registrosOrdenados = contarRun(runs.get(0));
        Files.deleteIfExists(runs.get(0));

        substituirArquivoPrincipal(arquivoPrincipal, arquivoOrdenado);
        limparDiretorio(diretorioTemp);
        return new ResultadoOrdenacao(registrosOrdenados, runsIniciais, passagens);
    }

    /** Distribuição em blocos de no máximo N registros ativos. */
    private List<Path> distribuir(Path arquivoPrincipal, Path pastaDistribuicao,
                                  int caminhos, int limiteMemoria) throws IOException {
        List<Path> runs = new ArrayList<>();
        for (int i = 0; i < caminhos; i++) {
            Files.createDirectories(pastaDistribuicao.resolve("caminho_" + i));
        }

        try (RandomAccessFile raf = new RandomAccessFile(arquivoPrincipal.toFile(), "r")) {
            raf.seek(Integer.BYTES);
            int indiceRun = 0;

            while (raf.getFilePointer() < raf.length()) {
                List<Carro> bloco = new ArrayList<>(limiteMemoria);

                while (bloco.size() < limiteMemoria && raf.getFilePointer() < raf.length()) {
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
                        Carro p = new Carro();
                        p.fromByteArray(dados);
                        bloco.add(p);
                    }
                }

                if (!bloco.isEmpty()) {
                    bloco.sort(Comparator.comparingInt(Carro::getId));
                    int caminho = indiceRun % caminhos;
                    Path run = pastaDistribuicao.resolve("caminho_" + caminho)
                            .resolve(String.format("bloco_%04d.run", indiceRun));
                    escreverRun(run, bloco);
                    runs.add(run);
                    indiceRun++;
                }
            }
        }
        return runs;
    }

    /** Intercala até K runs mantendo apenas um registro corrente de cada arquivo em memória. */
    private void intercalar(List<Path> entradas, Path saida) throws IOException {
        List<DataInputStream> streams = new ArrayList<>();
        PriorityQueue<ItemFila> fila = new PriorityQueue<>(Comparator.comparingInt(a -> a.carro.getId()));

        try {
            for (int i = 0; i < entradas.size(); i++) {
                DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(entradas.get(i))));
                streams.add(in);
                Carro primeiro = lerProximoRun(in);
                if (primeiro != null) fila.add(new ItemFila(primeiro, i));
            }

            if (saida.getParent() != null) Files.createDirectories(saida.getParent());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(saida)))) {
                while (!fila.isEmpty()) {
                    ItemFila menor = fila.poll();
                    escreverCarroRun(out, menor.carro);
                    Carro proximo = lerProximoRun(streams.get(menor.indiceStream));
                    if (proximo != null) fila.add(new ItemFila(proximo, menor.indiceStream));
                }
            }
        } finally {
            for (DataInputStream in : streams) {
                try { in.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void escreverRun(Path run, List<Carro> carros) throws IOException {
        if (run.getParent() != null) Files.createDirectories(run.getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(run)))) {
            for (Carro p : carros) escreverCarroRun(out, p);
        }
    }

    // Formato temporário: [int tamanho][bytes carro]. Não precisa de lápide em runs.
    private void escreverCarroRun(DataOutputStream out, Carro p) throws IOException {
        byte[] dados = p.toByteArray();
        out.writeInt(dados.length);
        out.write(dados);
    }

    private Carro lerProximoRun(DataInputStream in) throws IOException {
        try {
            int tamanho = in.readInt();
            if (tamanho < 0 || tamanho > 10_000_000) throw new IOException("Tamanho inválido em run temporário.");
            byte[] dados = new byte[tamanho];
            in.readFully(dados);
            Carro p = new Carro();
            p.fromByteArray(dados);
            return p;
        } catch (EOFException e) {
            return null;
        }
    }

    private int contarRun(Path run) throws IOException {
        int n = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(run)))) {
            while (lerProximoRun(in) != null) n++;
        }
        return n;
    }

    private void escreverArquivoFinal(Path runFinal, Path destino, int ultimoId) throws IOException {
        ArquivoSequencial novo = new ArquivoSequencial(destino);
        novo.reinicializar(ultimoId);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(runFinal)))) {
            Carro p;
            while ((p = lerProximoRun(in)) != null) novo.appendComId(p);
        }
    }

    /**
     * O arquivo ordenado é criado separadamente e só depois substitui dados.db.
     * O antigo vira backup; portanto todo CRUD futuro continua usando dados.db,
     * que passa a ser fisicamente o arquivo novo, ordenado e compactado.
     */
    private void substituirArquivoPrincipal(Path principal, Path ordenado) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        Path backup = principal.resolveSibling("dados_pre_ordenacao_" + timestamp + ".bak");
        Files.move(principal, backup, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(ordenado, principal, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Tenta restaurar o original caso a substituição final falhe.
            Files.move(backup, principal, StandardCopyOption.REPLACE_EXISTING);
            throw e;
        }
    }

    private void limparDiretorio(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    private static class ItemFila {
        final Carro carro;
        final int indiceStream;
        ItemFila(Carro carro, int indiceStream) {
            this.carro = carro;
            this.indiceStream = indiceStream;
        }
    }

    public static class ResultadoOrdenacao {
        public final int registrosOrdenados;
        public final int runsIniciais;
        public final int passagensIntercalacao;
        public ResultadoOrdenacao(int registrosOrdenados, int runsIniciais, int passagensIntercalacao) {
            this.registrosOrdenados = registrosOrdenados;
            this.runsIniciais = runsIniciais;
            this.passagensIntercalacao = passagensIntercalacao;
        }
        @Override
        public String toString() {
            return "registros=" + registrosOrdenados + ", blocos iniciais=" + runsIniciais +
                    ", passagens de intercalação=" + passagensIntercalacao;
        }
    }
}
