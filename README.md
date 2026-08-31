# TP1 AEDS III — Carros

## Integrantes

- Gabriel Benicio Fonseca
- Rhayner Martins

## Base utilizada

- Base: Auto MPG
- Fonte: [UCI Machine Learning Repository](https://archive.ics.uci.edu/dataset/9/auto)
- Arquivo do projeto: `data/base.csv`

O CSV contém um recorte didático de 60 automóveis. A carga converte esses dados
para o arquivo binário utilizado pelo CRUD sequencial.

## Campos

| Campo | Tipo | Uso |
|---|---|---|
| `id` | `int` | Identificador sequencial |
| `codigo` | `String` fixa | Código de 8 bytes ASCII, como `CAR00001` |
| `nome` | `String` variável | Nome do automóvel |
| `dataRegistro` | `LocalDate` | Data do registro |
| `caracteristicas` | `List<String>` | Lista de características |
| `ano` | `int` | Ano do automóvel |

Na serialização, `codigo` ocupa exatamente 8 bytes. O nome e cada item da lista
são gravados em formato UTF, e a data é armazenada como a quantidade de dias da
época (`LocalDate.toEpochDay()`).

## Arquivo binário

O arquivo principal é gerado em `data/dados.db` com o seguinte formato:

```text
[int ultimoId]
[byte lapide][int tamanhoRegistro][byte[] dadosObjeto]
[byte lapide][int tamanhoRegistro][byte[] dadosObjeto]
...
```

Convenção da lápide:

- `0`: registro ativo;
- `1`: registro excluído.

O cabeçalho preserva o maior ID já utilizado. IDs excluídos não são reutilizados.
O arquivo `data/dados.db` é gerado durante a carga e não é versionado.

## Carga do arquivo

A classe `service.Importador` lê `data/base.csv`, valida os campos, cria objetos
`Carro` e grava os registros ativos no arquivo binário. A carga reinicializa o
arquivo e atualiza o cabeçalho com o último ID importado.

## CRUD sequencial

### Create

Lê e incrementa `ultimoId`, atualiza o cabeçalho, atribui o novo ID e escreve o
registro ativo no fim do arquivo.

### Read

Percorre o arquivo depois do cabeçalho, ignora registros com lápide e retorna
somente a versão ativa do ID procurado.

### Update

- Mesmo tamanho: sobrescreve os bytes no endereço atual.
- Tamanho diferente: marca a versão anterior como excluída e grava a nova versão
  no fim do arquivo, preservando o ID.

Essa regra é aplicada quando o registro aumenta ou diminui.

### Delete

Altera apenas a lápide do registro ativo. A remoção física ocorre durante a
ordenação externa.

## Ordenação Externa

A opção de ordenação permite escolher entre duas formas de geração dos runs. As
duas terminam com intercalação balanceada e substituem `data/dados.db` por um
arquivo ordenado e compactado.

### Intercalação Balanceada Comum

1. Lê no máximo `N` registros ativos por bloco.
2. Ignora registros excluídos.
3. Ordena o bloco por ID com insertion sort implementado no projeto.
4. Grava um run ordenado.
5. Distribui os runs em round-robin pelo número de caminhos informado.
6. Intercala grupos de até `K` runs até restar um único run.

Na intercalação, somente o registro corrente de cada caminho permanece em
memória. A escolha da menor cabeça é feita por uma varredura manual.

### Seleção por Substituição

1. Preenche a memória com até `M` registros ativos.
2. Seleciona manualmente o menor registro não congelado.
3. Escreve o registro no run atual e lê o próximo registro do arquivo.
4. Mantém o novo registro elegível quando seu ID é maior ou igual ao último ID escrito.
5. Congela o registro para o run seguinte quando seu ID é menor.
6. Finaliza o run quando todos os itens em memória estão congelados.
7. Descongela os itens e inicia o próximo run.
8. Distribui os runs pelos caminhos e aplica a mesma intercalação balanceada.

A seleção do menor item não utiliza fila de prioridade. Em qualquer instante,
a memória contém no máximo `M` registros, além das cabeças da intercalação.

### Resultado da ordenação

O arquivo final:

- contém somente registros ativos;
- fica em ordem crescente de ID;
- remove fisicamente lápides e versões antigas de updates;
- preserva o `ultimoId` do cabeçalho;
- continua sendo utilizado pelo CRUD.

Antes da substituição, o arquivo ordenado é concluído separadamente. O arquivo
anterior é mantido como backup local ignorado pelo Git.

## Estrutura do projeto

```text
src/
├── Main.java
├── TesteTP1.java
├── dao/ArquivoSequencial.java
├── model/Carro.java
├── service/Importador.java
├── service/OrdenacaoExterna.java
└── util/CsvUtil.java
data/
└── base.csv
temp/
└── .gitkeep
```

## Como compilar

No Windows:

```bat
compilar.bat
```

Ou diretamente:

```text
javac -encoding UTF-8 -d out src/Main.java src/TesteTP1.java src/model/Carro.java src/dao/ArquivoSequencial.java src/service/Importador.java src/service/OrdenacaoExterna.java src/util/CsvUtil.java
```

## Como executar

```bat
executar.bat
```

Ou:

```text
java -cp out Main
```

## Como testar

```bat
testar.bat
```

Ou:

```text
java -cp out TesteTP1
```

Os testes cobrem carga, Create, Read, os dois casos de Update, Delete, as duas
estratégias de ordenação e o CRUD depois de cada estratégia. Também verificam:

- ausência de registros perdidos ou duplicados;
- ordem crescente dos IDs;
- remoção física das lápides;
- preservação do último ID;
- runs ordenados na seleção por substituição;
- congelamento de registros;
- parâmetros e arquivos inválidos;
- EOF inesperado.

O cenário da intercalação comum usa 4 caminhos e 7 registros em memória. O
cenário da seleção por substituição usa 4 caminhos, memória 5 e IDs em ordem
física propositalmente irregular.

Resultados obtidos na execução de teste:

```text
Intercalação Balanceada: registros=59, runs iniciais=9, passagens=2
Seleção por Substituição: registros=17, runs iniciais=2, passagens=1, congelados=5
CRUD após ordenação: OK
```

O escopo deste TP1 termina no CRUD sequencial e na ordenação externa. Estruturas
de indexação, compressão, casamento de padrões e criptografia não são
implementadas nesta etapa.
