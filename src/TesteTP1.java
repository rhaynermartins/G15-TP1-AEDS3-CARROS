import dao.ArquivoSequencial;
import model.Carro;
import service.Importador;
import service.OrdenacaoExterna;
import util.CsvUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Testes de integração simples, sem framework externo. */
public class TesteTP1 {
    private static final Path RAIZ = Paths.get("build-test");
    private static final Path CSV_PRINCIPAL = Paths.get("data", "base.csv");

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--base-100k")) {
            testarBase100K();
            return;
        }
        apagar(RAIZ);
        Files.createDirectories(RAIZ);

        ResultadoCenario comum = testarCrudEIntercalacaoComum();
        ResultadoCenario selecao = testarSelecaoPorSubstituicao();
        testarValidacoes();

        System.out.println();
        System.out.println("TODOS OS TESTES OBRIGATÓRIOS PASSARAM.");
        System.out.println("Intercalação Balanceada: " + comum.ordenacao);
        System.out.println("Seleção por Substituição: " + selecao.ordenacao
                + ", registros congelados=" + selecao.ordenacao.registrosCongelados);
    }

    private static ResultadoCenario testarCrudEIntercalacaoComum() throws Exception {
        Path cenario = RAIZ.resolve("comum");
        Files.createDirectories(cenario.resolve("data"));
        Path db = cenario.resolve("data/dados.db");
        ArquivoSequencial arquivo = new ArquivoSequencial(db);
        Path csvPequeno = cenario.resolve("data/base-pequena.csv");
        criarCsvPequeno(csvPequeno, 60);

        System.out.println("TESTE 1 — CARGA");
        Importador.ResultadoImportacao imp = new Importador().carregarBase(csvPequeno, db);
        exigir(imp.importados == 60, "Devem ser importados 60 registros");
        exigir(arquivo.getUltimoId() == 60, "Cabeçalho deve ser 60");
        exigir(arquivo.contarAtivos() == 60, "Devem existir 60 registros ativos");
        exigir(arquivo.read(1) != null && !arquivo.read(1).getNome().isBlank(),
                "Leitura do primeiro registro");
        exigir(arquivo.read(1).getCodigo().equals("CAR000000001"),
                "Código fixo do primeiro registro");
        ok();

        System.out.println("TESTE 2 — CREATE");
        Carro novo = carro(0, "honda civic teste", 2024);
        int idNovo = arquivo.create(novo);
        exigir(idNovo == 61, "Novo ID deve ser 61");
        exigir(arquivo.read(61) != null, "Registro criado deve ser lido");
        ok();

        System.out.println("TESTE 3 — READ");
        exigir(arquivo.read(25) != null, "ID existente deve ser encontrado");
        exigir(arquivo.read(99999) == null, "ID inexistente deve retornar null");
        exigir(arquivo.delete(2), "Delete do ID 2 deve funcionar");
        exigir(arquivo.read(2) == null, "ID apagado não deve ser lido");
        ok();

        System.out.println("TESTE 4 — UPDATE MESMO TAMANHO");
        Carro carro10 = arquivo.read(10);
        ArquivoSequencial.InfoRegistro antes10 = arquivo.localizarFisicamenteAtivo(10);
        long tamanhoAntes = Files.size(db);
        carro10.setAno(1972); // int ocupa sempre 4 bytes
        exigir(arquivo.update(carro10), "Update mesmo tamanho deve funcionar");
        ArquivoSequencial.InfoRegistro depois10 = arquivo.localizarFisicamenteAtivo(10);
        exigir(antes10.posicao == depois10.posicao, "Mesmo tamanho deve manter endereço");
        exigir(Files.size(db) == tamanhoAntes, "Mesmo tamanho não pode aumentar arquivo");
        exigir(arquivo.read(10).getAno() == 1972, "Novo valor deve ser persistido");
        ok();

        System.out.println("TESTE 5 — UPDATE TAMANHO DIFERENTE");
        Carro carro11 = arquivo.read(11);
        long posAntiga11 = arquivo.localizarFisicamenteAtivo(11).posicao;
        carro11.setNome(carro11.getNome() + "-versao-com-nome-maior");
        exigir(arquivo.update(carro11), "Update tamanho diferente deve funcionar");
        exigir(arquivo.localizarFisicamenteAtivo(11).posicao != posAntiga11,
                "Tamanho diferente deve ir para o fim");
        exigir(arquivo.read(11).getId() == 11, "ID deve permanecer 11");
        exigir(arquivo.contarLapides() >= 2, "Devem existir lápides");
        ok();

        System.out.println("TESTE 6 — DELETE");
        exigir(arquivo.delete(3), "Delete deve marcar lápide");
        exigir(arquivo.read(3) == null, "Registro apagado não aparece no read");
        exigir(!contemId(arquivo.listarAtivos(), 3), "Registro apagado não aparece na listagem");
        ok();

        System.out.println("TESTE 7A — INTERCALAÇÃO BALANCEADA COMUM");
        int ultimoIdAntes = arquivo.getUltimoId();
        Set<Integer> idsAntes = idsAtivos(arquivo);
        exigir(arquivo.contarLapides() > 0, "Pré-condição: deve haver lápides");
        OrdenacaoExterna.ResultadoOrdenacao ordenacao = new OrdenacaoExterna()
                .ordenar(db, cenario.resolve("temp"), 4, 7);
        validarResultadoOrdenacao(arquivo, idsAntes, ultimoIdAntes, ordenacao);
        exigir(ordenacao.runsIniciais > 1, "Memória 7 deve gerar vários runs");
        exigir(ordenacao.passagensIntercalacao > 1, "O teste deve realizar múltiplas passagens");
        ok();

        System.out.println("TESTE 8 — CRUD APÓS INTERCALAÇÃO BALANCEADA");
        testarCrudDepoisDaOrdenacao(arquivo);
        ok();
        return new ResultadoCenario(ordenacao);
    }

    private static ResultadoCenario testarSelecaoPorSubstituicao() throws Exception {
        Path cenario = RAIZ.resolve("selecao");
        Files.createDirectories(cenario.resolve("data"));
        Path db = cenario.resolve("data/dados.db");
        ArquivoSequencial arquivo = new ArquivoSequencial(db);
        arquivo.reinicializar(30);

        // Ordem física propositalmente irregular e maior que a memória M=5.
        int[] ids = {20, 3, 17, 1, 15, 2, 14, 4, 13, 5, 12, 6, 11, 7, 10, 8, 9, 16};
        for (int id : ids) arquivo.appendComId(carro(id, "carro-" + id, 1970 + id));
        exigir(arquivo.delete(4), "Cenário deve conter uma exclusão lógica");
        Carro alterado = arquivo.read(13);
        alterado.setNome("carro-13-versao-maior");
        exigir(arquivo.update(alterado), "Cenário deve conter versão antiga de update");

        System.out.println("TESTE 7B — SELEÇÃO POR SUBSTITUIÇÃO + INTERCALAÇÃO");
        int ultimoIdAntes = arquivo.getUltimoId();
        Set<Integer> idsAntes = idsAtivos(arquivo);
        OrdenacaoExterna.ResultadoOrdenacao ordenacao = new OrdenacaoExterna()
                .ordenarComSelecaoPorSubstituicao(db, cenario.resolve("temp"), 4, 5);
        validarResultadoOrdenacao(arquivo, idsAntes, ultimoIdAntes, ordenacao);
        exigir(ordenacao.runsIniciais > 1, "Seleção com M=5 deve gerar múltiplos runs");
        exigir(ordenacao.passagensIntercalacao >= 1, "Runs devem ser intercalados");
        exigir(ordenacao.registrosCongelados > 0, "Registros devem ser congelados");
        exigir(ordenacao.runsIniciaisOrdenados, "Cada run interno deve permanecer ordenado");
        ok();

        System.out.println("TESTE 9 — CRUD APÓS SELEÇÃO POR SUBSTITUIÇÃO");
        testarCrudDepoisDaOrdenacao(arquivo);
        ok();
        return new ResultadoCenario(ordenacao);
    }

    /** Validação e benchmark executados separadamente da suíte funcional rápida. */
    private static void testarBase100K() throws Exception {
        Path db = Paths.get("data", "dados.db");
        Path temporarios = Paths.get("temp", "base-100k");
        apagar(temporarios);

        System.out.println("TESTE BASE 100.000 — INTEGRIDADE DO CSV");
        int registrosCsv = validarCsvPrincipal(CSV_PRINCIPAL);
        exigir(registrosCsv == 100_000, "CSV deve conter exatamente 100.000 registros");
        ok();

        System.out.println("TESTE BASE 100.000 — CARGA COMPLETA");
        long inicioCarga = System.nanoTime();
        Importador.ResultadoImportacao importacao = new Importador()
                .carregarBase(CSV_PRINCIPAL, db);
        long tempoCargaMs = nanosParaMillis(System.nanoTime() - inicioCarga);
        long tamanhoDbAposCarga = Files.size(db);
        ArquivoSequencial arquivo = new ArquivoSequencial(db);

        exigir(importacao.importados == 100_000, "Carga deve importar 100.000 registros");
        exigir(importacao.ignorados == 0, "Carga não deve ignorar registros");
        exigir(arquivo.getUltimoId() == 100_000, "Último ID da carga deve ser 100000");
        exigir(arquivo.contarAtivos() == 100_000, "Devem existir 100.000 registros ativos");
        validarLeituraECodigo(arquivo, 1, "CAR000000001");
        validarLeituraECodigo(arquivo, 50_000, "CAR000050000");
        validarLeituraECodigo(arquivo, 99_999, "CAR000099999");
        validarLeituraECodigo(arquivo, 100_000, "CAR000100000");
        exigir(arquivo.read(100_001) == null, "ID 100001 não deve existir antes do Create");
        ok();

        System.out.println("TESTE BASE 100.000 — INTERCALAÇÃO BALANCEADA");
        long inicioOrdenacao = System.nanoTime();
        OrdenacaoExterna.ResultadoOrdenacao ordenacao = new OrdenacaoExterna()
                .ordenar(db, temporarios, 4, 1_000);
        long tempoOrdenacaoMs = nanosParaMillis(System.nanoTime() - inicioOrdenacao);

        List<Carro> ordenados = arquivo.listarAtivos();
        boolean[] idsEncontrados = new boolean[100_001];
        int duplicados = 0;
        for (int i = 0; i < ordenados.size(); i++) {
            Carro atual = ordenados.get(i);
            exigir(atual.getId() == i + 1, "IDs devem permanecer consecutivos e crescentes");
            if (idsEncontrados[atual.getId()]) duplicados++;
            idsEncontrados[atual.getId()] = true;
        }
        exigir(ordenacao.registrosOrdenados == 100_000, "Ordenação deve processar 100.000 registros");
        exigir(ordenacao.runsIniciais > 1, "Ordenação grande deve gerar múltiplos runs");
        exigir(ordenacao.passagensIntercalacao > 1, "Ordenação grande deve ter múltiplas passagens");
        exigir(ordenados.size() == 100_000, "Ordenação não pode perder registros");
        exigir(duplicados == 0, "Ordenação não pode duplicar IDs");
        exigir(arquivo.contarLapides() == 0, "Ordenação deve remover todas as lápides");
        exigir(arquivo.getUltimoId() == 100_000, "Ordenação deve preservar ultimoId");
        ok();

        System.out.println("TESTE BASE 100.000 — CRUD APÓS ORDENAÇÃO");
        Carro criado = carro(0, "veiculo-teste-100001", 2025);
        int novoId = arquivo.create(criado);
        exigir(novoId == 100_001, "Create após carga deve gerar ID 100001");
        exigir(criado.getCodigo().equals("CAR000100001"), "Código do novo registro deve usar 12 bytes");
        exigir(arquivo.read(novoId) != null, "Read do ID 100001 deve funcionar");

        ArquivoSequencial.InfoRegistro antesMesmoTamanho = arquivo.localizarFisicamenteAtivo(novoId);
        long tamanhoAntesMesmoTamanho = Files.size(db);
        criado.setAno(2026);
        exigir(arquivo.update(criado), "Update de mesmo tamanho na base grande");
        exigir(arquivo.localizarFisicamenteAtivo(novoId).posicao == antesMesmoTamanho.posicao,
                "Update de mesmo tamanho deve manter endereço");
        exigir(Files.size(db) == tamanhoAntesMesmoTamanho,
                "Update de mesmo tamanho não deve aumentar o arquivo");

        long posicaoAntesTamanhoDiferente = arquivo.localizarFisicamenteAtivo(novoId).posicao;
        criado.setNome("veiculo-teste-100001-com-nome-maior");
        exigir(arquivo.update(criado), "Update de tamanho diferente na base grande");
        exigir(arquivo.localizarFisicamenteAtivo(novoId).posicao != posicaoAntesTamanhoDiferente,
                "Update de tamanho diferente deve mover o registro");
        exigir(arquivo.delete(novoId), "Delete na base grande");
        exigir(arquivo.read(novoId) == null, "Registro excluído não deve ser lido");
        ok();

        apagar(temporarios);
        System.out.println("BASE100K_RESULTADO");
        System.out.println("registros_csv=" + registrosCsv);
        System.out.println("importados=" + importacao.importados);
        System.out.println("ignorados=" + importacao.ignorados);
        System.out.println("tamanho_db_apos_carga_bytes=" + tamanhoDbAposCarga);
        System.out.println("tempo_carga_ms=" + tempoCargaMs);
        System.out.println("tempo_ordenacao_ms=" + tempoOrdenacaoMs);
        System.out.println("runs_iniciais=" + ordenacao.runsIniciais);
        System.out.println("passagens=" + ordenacao.passagensIntercalacao);
        System.out.println("registros_finais=" + ordenacao.registrosOrdenados);
        System.out.println("ids_duplicados=" + duplicados);
        System.out.println("lapides_finais=0");
        System.out.println("ultimo_id_apos_ordenacao=100000");
        System.out.println("codigo_id_1=CAR000000001");
        System.out.println("codigo_id_99999=CAR000099999");
        System.out.println("codigo_id_100000=CAR000100000");
        System.out.println("codigo_id_100001=CAR000100001");
    }

    private static int validarCsvPrincipal(Path csv) throws IOException {
        exigir(Files.exists(csv), "data/base.csv deve existir");
        int registros = 0;
        try (BufferedReader leitor = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String cabecalho = leitor.readLine();
            exigir("nome;caracteristicas;ano;data_registro".equals(cabecalho),
                    "Cabeçalho do CSV principal");
            String linha;
            while ((linha = leitor.readLine()) != null) {
                List<String> campos = CsvUtil.separarLinha(linha, ';');
                exigir(campos.size() == 4, "Cada linha do CSV deve possuir quatro campos");
                exigir(!campos.get(0).isBlank(), "Nome não pode ser vazio");
                int ano = Integer.parseInt(campos.get(2));
                exigir(ano >= 1886 && ano <= LocalDate.now().getYear() + 1, "Ano válido");
                LocalDate.parse(campos.get(3));
                registros++;
            }
        }
        return registros;
    }

    private static void validarLeituraECodigo(
            ArquivoSequencial arquivo, int id, String codigoEsperado) throws IOException {
        Carro carro = arquivo.read(id);
        exigir(carro != null, "ID " + id + " deve existir");
        exigir(carro.getCodigo().equals(codigoEsperado), "Código correto para ID " + id);
        exigir(carro.getCodigo().getBytes(StandardCharsets.US_ASCII).length == 12,
                "Código deve ocupar 12 bytes ASCII");
    }

    private static long nanosParaMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static void criarCsvPequeno(Path destino, int quantidade) throws IOException {
        Files.createDirectories(destino.getParent());
        try (BufferedReader leitor = Files.newBufferedReader(CSV_PRINCIPAL, StandardCharsets.UTF_8);
             BufferedWriter escritor = Files.newBufferedWriter(destino, StandardCharsets.UTF_8)) {
            for (int i = 0; i <= quantidade; i++) {
                String linha = leitor.readLine();
                if (linha == null) throw new IOException("CSV principal possui registros insuficientes.");
                escritor.write(linha);
                escritor.newLine();
            }
        }
    }

    private static void testarValidacoes() throws Exception {
        System.out.println("TESTE 10 — VALIDAÇÕES E ARQUIVOS INVÁLIDOS");
        Path cenario = RAIZ.resolve("validacoes");
        Files.createDirectories(cenario);
        Path db = cenario.resolve("dados.db");
        ArquivoSequencial arquivo = new ArquivoSequencial(db);
        arquivo.garantirArquivo();

        exigir(arquivo.read(999) == null, "Read inexistente deve retornar null");
        exigir(!arquivo.update(carro(999, "inexistente", 2000)),
                "Update inexistente deve retornar false");
        exigir(!arquivo.delete(999), "Delete inexistente deve retornar false");
        exigirExcecao(() -> new OrdenacaoExterna().ordenar(db, cenario.resolve("temp"), 1, 5),
                IllegalArgumentException.class, "Caminhos menores que 2");
        exigirExcecao(() -> new OrdenacaoExterna().ordenar(db, cenario.resolve("temp"), 4, 0),
                IllegalArgumentException.class, "Memória igual a zero");
        exigirExcecao(() -> new Importador().carregarBase(cenario.resolve("ausente.csv"), db),
                IOException.class, "CSV inexistente");

        Path corrompido = cenario.resolve("corrompido.db");
        Files.write(corrompido, new byte[] {0, 0, 0, 1, 0, 0});
        exigirExcecao(() -> new OrdenacaoExterna().ordenar(
                corrompido, cenario.resolve("temp-corrompido"), 4, 5),
                IOException.class, "EOF inesperado");
        ok();
    }

    private static void validarResultadoOrdenacao(
            ArquivoSequencial arquivo, Set<Integer> idsAntes, int ultimoIdAntes,
            OrdenacaoExterna.ResultadoOrdenacao resultado) throws IOException {
        List<Carro> depois = arquivo.listarAtivos();
        Set<Integer> idsDepois = idsAtivos(arquivo);
        exigir(resultado.registrosOrdenados == idsAntes.size(), "Contagem processada deve ser exata");
        exigir(depois.size() == idsAntes.size(), "Nenhum registro pode ser perdido ou duplicado");
        exigir(idsDepois.equals(idsAntes), "O conjunto de IDs deve ser preservado");
        exigir(idsDepois.size() == depois.size(), "Nenhum ID pode aparecer duas vezes");
        exigir(arquivo.contarLapides() == 0, "Lápides devem ser removidas fisicamente");
        exigir(arquivo.getUltimoId() == ultimoIdAntes, "Último ID deve ser preservado");
        for (int i = 1; i < depois.size(); i++) {
            exigir(depois.get(i - 1).getId() < depois.get(i).getId(),
                    "IDs devem estar em ordem crescente");
        }
    }

    private static void testarCrudDepoisDaOrdenacao(ArquivoSequencial arquivo) throws IOException {
        Carro novo = carro(0, "pos-ordenacao", 2025);
        int id = arquivo.create(novo);
        exigir(arquivo.read(id) != null, "Create/read após ordenação");
        novo.setId(id);
        novo.setNome("pos-ordenacao-com-nome-maior");
        exigir(arquivo.update(novo), "Update após ordenação");
        exigir(arquivo.read(id) != null
                && arquivo.read(id).getNome().contains("maior"), "Read após update");
        exigir(arquivo.delete(id), "Delete após ordenação");
        exigir(arquivo.read(id) == null, "Delete após ordenação deve refletir no read");
    }

    private static Carro carro(int id, String nome, int ano) {
        String codigo = id > 0 ? Carro.codigoPorId(id) : "";
        return new Carro(id, codigo, nome, LocalDate.of(2026, 8, 31),
                Arrays.asList("origem-teste", "4-cilindros"), ano);
    }

    private static Set<Integer> idsAtivos(ArquivoSequencial arquivo) throws IOException {
        Set<Integer> ids = new HashSet<>();
        for (Carro carro : arquivo.listarAtivos()) ids.add(carro.getId());
        return ids;
    }

    private static boolean contemId(List<Carro> carros, int id) {
        for (Carro carro : carros) if (carro.getId() == id) return true;
        return false;
    }

    private static void exigir(boolean condicao, String mensagem) {
        if (!condicao) throw new AssertionError("FALHOU: " + mensagem);
    }

    private static void exigirExcecao(Acao acao, Class<? extends Throwable> tipo,
                                      String mensagem) throws Exception {
        try {
            acao.executar();
        } catch (Throwable e) {
            if (tipo.isInstance(e)) return;
            throw e;
        }
        throw new AssertionError("FALHOU: exceção esperada para " + mensagem);
    }

    private static void ok() { System.out.println("OK\n"); }

    private static void apagar(Path caminho) throws IOException {
        if (!Files.exists(caminho)) return;
        if (Files.isDirectory(caminho)) {
            try (DirectoryStream<Path> filhos = Files.newDirectoryStream(caminho)) {
                for (Path filho : filhos) apagar(filho);
            }
        }
        Files.deleteIfExists(caminho);
    }

    private interface Acao { void executar() throws Exception; }

    private static class ResultadoCenario {
        final OrdenacaoExterna.ResultadoOrdenacao ordenacao;
        ResultadoCenario(OrdenacaoExterna.ResultadoOrdenacao ordenacao) {
            this.ordenacao = ordenacao;
        }
    }
}
