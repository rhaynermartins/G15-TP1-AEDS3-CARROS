package model;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Entidade principal do TP1.
 *
 * Requisitos atendidos:
 * - String de tamanho fixo: codigo (12 bytes ASCII no arquivo serializado);
 * - String variável: nome;
 * - Data: dataRegistro;
 * - Lista: caracteristicas;
 * - Inteiro: ano.
 */
public class Carro {
    public static final int TAMANHO_CODIGO = 12;

    private int id;
    private String codigo;
    private String nome;
    private LocalDate dataRegistro;
    private List<String> caracteristicas;
    private int ano;

    public Carro() {
        this(0, "", "", LocalDate.now(), new ArrayList<>(), 0);
    }

    public Carro(int id, String codigo, String nome, LocalDate dataRegistro, List<String> caracteristicas, int ano) {
        this.id = id;
        this.codigo = codigo == null ? "" : codigo;
        this.nome = nome == null ? "" : nome;
        this.dataRegistro = dataRegistro == null ? LocalDate.now() : dataRegistro;
        this.caracteristicas = caracteristicas == null ? new ArrayList<>() : new ArrayList<>(caracteristicas);
        this.ano = ano;
    }

    /**
     * Serializa o objeto em uma sequência reversível de bytes.
     * O código ocupa EXATAMENTE 12 bytes. Os demais campos carregam metadados
     * suficientes para que fromByteArray reconstrua o objeto integralmente.
     */
    public byte[] toByteArray() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(id);

            byte[] codigoBytes = normalizarCodigo(codigo).getBytes(StandardCharsets.US_ASCII);
            dos.write(codigoBytes, 0, TAMANHO_CODIGO);

            dos.writeUTF(nome);
            dos.writeLong(dataRegistro.toEpochDay());

            dos.writeInt(caracteristicas.size());
            for (String caracteristica : caracteristicas) {
                dos.writeUTF(caracteristica == null ? "" : caracteristica);
            }

            dos.writeInt(ano);
        }
        return baos.toByteArray();
    }

    /**
     * Desserializa exatamente na mesma ordem usada por toByteArray().
     */
    public void fromByteArray(byte[] dados) throws IOException {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(dados))) {
            id = dis.readInt();

            byte[] codigoBytes = new byte[TAMANHO_CODIGO];
            dis.readFully(codigoBytes);
            codigo = new String(codigoBytes, StandardCharsets.US_ASCII).trim();

            nome = dis.readUTF();
            dataRegistro = LocalDate.ofEpochDay(dis.readLong());

            int quantidadeCaracteristicas = dis.readInt();
            if (quantidadeCaracteristicas < 0 || quantidadeCaracteristicas > 1000) {
                throw new IOException("Quantidade de caracteristicas inválida no registro: " + quantidadeCaracteristicas);
            }
            caracteristicas = new ArrayList<>();
            for (int i = 0; i < quantidadeCaracteristicas; i++) {
                caracteristicas.add(dis.readUTF());
            }

            ano = dis.readInt();
        }
    }

    /** Garante exatamente 12 caracteres ASCII, preenchendo com espaços quando necessário. */
    public static String normalizarCodigo(String valor) {
        String s = valor == null ? "" : valor.trim().toUpperCase();
        if (s.length() > TAMANHO_CODIGO) {
            s = s.substring(0, TAMANHO_CODIGO);
        }
        return String.format("%-" + TAMANHO_CODIGO + "s", s);
    }

    public static String codigoPorId(int id) {
        return String.format("CAR%09d", id);
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo == null ? "" : codigo; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome == null ? "" : nome; }
    public LocalDate getDataRegistro() { return dataRegistro; }
    public void setDataRegistro(LocalDate dataRegistro) { this.dataRegistro = dataRegistro; }
    public List<String> getCaracteristicas() { return new ArrayList<>(caracteristicas); }
    public void setCaracteristicas(List<String> caracteristicas) { this.caracteristicas = caracteristicas == null ? new ArrayList<>() : new ArrayList<>(caracteristicas); }
    public int getAno() { return ano; }
    public void setAno(int ano) { this.ano = ano; }

    @Override
    public String toString() {
        return "Carro{" +
                "id=" + id +
                ", codigo='" + codigo + '\'' +
                ", nome='" + nome + '\'' +
                ", dataRegistro=" + dataRegistro +
                ", caracteristicas=" + caracteristicas +
                ", ano=" + ano +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Carro)) return false;
        Carro carro = (Carro) o;
        return id == carro.id && ano == carro.ano &&
                Objects.equals(codigo, carro.codigo) && Objects.equals(nome, carro.nome) &&
                Objects.equals(dataRegistro, carro.dataRegistro) && Objects.equals(caracteristicas, carro.caracteristicas);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, codigo, nome, dataRegistro, caracteristicas, ano);
    }
}
