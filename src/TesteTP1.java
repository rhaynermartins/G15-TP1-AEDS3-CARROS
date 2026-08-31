import dao.ArquivoSequencial;
import model.Carro;
import service.Importador;
import service.OrdenacaoExterna;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Testes de integração simples, sem framework externo. */
public class TesteTP1 {
    public static void main(String[] args) throws Exception {
        Path raiz = Paths.get("build-test");
        apagar(raiz);
        Files.createDirectories(raiz.resolve("data"));
        Files.createDirectories(raiz.resolve("temp"));

        Path csv = Paths.get("data", "base.csv");
        Path db = raiz.resolve("data/dados.db");
        ArquivoSequencial arq = new ArquivoSequencial(db);

        System.out.println("TESTE 1 — CARGA");
        Importador.ResultadoImportacao imp = new Importador().carregarBase(csv, db);
        exigir(imp.importados == 60, "Devem ser importados 60 registros");
        exigir(arq.getUltimoId() == 60, "Cabeçalho deve ser 60");
        exigir(arq.contarAtivos() == 60, "Devem existir 60 registros ativos");
        exigir(arq.read(1) != null && arq.read(1).getNome().equals("chevrolet chevelle malibu"), "Leitura do primeiro registro");
        ok();

        System.out.println("TESTE 2 — CREATE");
        Carro novo = new Carro(0, "", "honda civic teste", LocalDate.of(2026, 8, 31), Arrays.asList("origem-japao", "4-cilindros"), 2024);
        int idNovo = arq.create(novo);
        exigir(idNovo == 61, "Novo ID deve ser 61");
        exigir(arq.read(61) != null, "Registro criado deve ser lido");
        ok();

        System.out.println("TESTE 3 — READ");
        exigir(arq.read(25) != null, "ID existente deve ser encontrado");
        exigir(arq.read(99999) == null, "ID inexistente deve retornar null");
        exigir(arq.delete(2), "Delete do ID 2 deve funcionar");
        exigir(arq.read(2) == null, "ID apagado não deve ser lido");
        ok();

        System.out.println("TESTE 4 — UPDATE MESMO TAMANHO");
        Carro p10 = arq.read(10);
        ArquivoSequencial.InfoRegistro antes10 = arq.localizarFisicamenteAtivo(10);
        long tamanhoArquivoAntes = Files.size(db);
        p10.setAno(1972); // int ocupa sempre 4 bytes, logo o registro mantém o mesmo tamanho
        exigir(arq.update(p10), "Update mesmo tamanho deve funcionar");
        ArquivoSequencial.InfoRegistro depois10 = arq.localizarFisicamenteAtivo(10);
        exigir(antes10.posicao == depois10.posicao, "Mesmo tamanho deve manter endereço físico");
        exigir(Files.size(db) == tamanhoArquivoAntes, "Mesmo tamanho não pode aumentar arquivo");
        exigir(arq.read(10).getAno() == 1972, "Novo valor deve ser persistido");
        ok();

        System.out.println("TESTE 5 — UPDATE TAMANHO DIFERENTE");
        Carro p11 = arq.read(11);
        long posAntiga11 = arq.localizarFisicamenteAtivo(11).posicao;
        p11.setNome(p11.getNome() + "-versao-com-nome-maior");
        exigir(arq.update(p11), "Update tamanho diferente deve funcionar");
        ArquivoSequencial.InfoRegistro nova11 = arq.localizarFisicamenteAtivo(11);
        exigir(nova11.posicao != posAntiga11, "Tamanho diferente deve ir para o fim");
        exigir(arq.read(11).getId() == 11, "ID deve permanecer 11");
        exigir(arq.contarLapides() >= 2, "Devem existir lápides (delete + versão antiga)");
        ok();

        System.out.println("TESTE 6 — DELETE");
        exigir(arq.delete(3), "Delete deve marcar lápide");
        exigir(arq.read(3) == null, "Registro apagado não aparece no read");
        exigir(arq.listarAtivos().stream().noneMatch(p -> p.getId() == 3), "Registro apagado não aparece na listagem");
        ok();

        System.out.println("TESTE 7 — ORDENAÇÃO EXTERNA");
        int ativosAntes = arq.contarAtivos();
        int lapidesAntes = arq.contarLapides();
        exigir(lapidesAntes > 0, "Pré-condição: deve haver lápides");
        OrdenacaoExterna.ResultadoOrdenacao ord = new OrdenacaoExterna().ordenar(db, raiz.resolve("temp"), 4, 7);
        exigir(ord.runsIniciais > 1, "Com limite 7 devem existir vários blocos externos");
        exigir(arq.contarAtivos() == ativosAntes, "Ordenação não pode perder registros ativos");
        exigir(arq.contarLapides() == 0, "Ordenação deve remover lápides fisicamente");
        List<Carro> ordenados = arq.listarAtivos();
        for (int i = 1; i < ordenados.size(); i++) {
            exigir(ordenados.get(i - 1).getId() < ordenados.get(i).getId(), "IDs devem estar em ordem crescente");
        }
        ok();

        System.out.println("TESTE 8 — CRUD DEPOIS DA ORDENAÇÃO");
        Carro pos = new Carro(0, "", "pos-sort", LocalDate.of(2026, 8, 31), Arrays.asList("origem-europa", "4-cilindros"), 2025);
        int idPos = arq.create(pos);
        exigir(arq.read(idPos) != null, "Create/read após sort");
        pos.setId(idPos);
        pos.setNome("pos-sort-com-nome-maior");
        exigir(arq.update(pos), "Update após sort");
        exigir(arq.read(idPos) != null && arq.read(idPos).getNome().contains("maior"), "Read após update pós-sort");
        exigir(arq.delete(idPos), "Delete após sort");
        exigir(arq.read(idPos) == null, "Delete pós-sort deve refletir no read");
        ok();

        System.out.println();
        System.out.println("TODOS OS TESTES OBRIGATÓRIOS PASSARAM.");
        System.out.println("Resultado da ordenação: " + ord);
    }

    private static void exigir(boolean condicao, String mensagem) {
        if (!condicao) throw new AssertionError("FALHOU: " + mensagem);
    }

    private static void ok() { System.out.println("OK\n"); }

    private static void apagar(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (java.util.stream.Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.deleteIfExists(x); } catch (IOException ignored) {}
            });
        }
    }
}
