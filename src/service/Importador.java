package service;

import dao.ArquivoSequencial;
import model.Carro;
import util.CsvUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Importa o CSV preparado a partir da base pública para o arquivo sequencial. */
public class Importador {

    public ResultadoImportacao carregarBase(Path csv, Path db) throws IOException {
        if (!Files.exists(csv)) throw new IOException("CSV não encontrado: " + csv);

        ArquivoSequencial arquivo = new ArquivoSequencial(db);
        arquivo.reinicializar(0);

        int id = 0;
        int ignoradas = 0;
        boolean primeiraLinha = true;

        try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String linha;
            while ((linha = br.readLine()) != null) {
                if (linha.isBlank()) continue;

                if (primeiraLinha) {
                    primeiraLinha = false;
                    String normalizada = linha.toLowerCase();
                    if (normalizada.contains("nome") && normalizada.contains("caracteristicas")) continue;
                }

                try {
                    List<String> c = CsvUtil.separarLinha(linha, ';');
                    if (c.size() < 4) throw new IllegalArgumentException("Linha com menos de 4 campos");

                    String nome = c.get(0).trim();
                    if (nome.isEmpty()) throw new IllegalArgumentException("Nome vazio");

                    List<String> caracteristicas = new ArrayList<>();
                    if (!c.get(1).isBlank()) {
                        Arrays.stream(c.get(1).split("\\|"))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .forEach(caracteristicas::add);
                    }

                    int ano = Integer.parseInt(c.get(2).trim());
                    LocalDate data = LocalDate.parse(c.get(3).trim());

                    id++;
                    Carro p = new Carro(id, Carro.codigoPorId(id), nome, data, caracteristicas, ano);
                    arquivo.appendComId(p);
                } catch (IllegalArgumentException | DateTimeParseException e) {
                    ignoradas++;
                    System.err.println("Linha ignorada por dado inválido: " + linha + " | motivo: " + e.getMessage());
                }
            }
        }

        arquivo.setUltimoId(id);
        return new ResultadoImportacao(id, ignoradas);
    }

    public static class ResultadoImportacao {
        public final int importados;
        public final int ignorados;
        public ResultadoImportacao(int importados, int ignorados) {
            this.importados = importados;
            this.ignorados = ignorados;
        }
        @Override
        public String toString() {
            return "Importados=" + importados + ", ignorados=" + ignorados;
        }
    }
}
