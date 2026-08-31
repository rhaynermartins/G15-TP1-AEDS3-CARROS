# TP1 AEDS III — Grupo 15

## Integrantes
- Gabriel Benicio Fonseca
- Rhayner Martins

## Tema
**Carros**

## Base utilizada
- **Nome:** Auto MPG
- **Fonte pública:** UCI Machine Learning Repository
- **Página oficial:** https://archive.ics.uci.edu/dataset/9/auto
- **Descrição:** base pública clássica com dados de automóveis, incluindo nome do carro, ano do modelo, cilindros, origem e dados de consumo/desempenho.

Para deixar o TP1 simples de explicar e adequado aos tipos exigidos, foi preparado em `data/base.csv` um recorte didático com 60 carros. Os campos foram normalizados para a entidade usada no projeto.

A data `data_registro` é derivada do ano do modelo e foi incluída para atender explicitamente ao requisito de um campo de data. O campo `codigo` é gerado sequencialmente pela aplicação e possui tamanho físico fixo de 8 bytes.

## Entidade
Classe: `model.Carro`

| Campo | Tipo Java | Requisito atendido |
|---|---|---|
| `id` | `int` | Identificador sequencial |
| `codigo` | `String` | String de tamanho fixo: 8 bytes ASCII (`CAR00001`) |
| `nome` | `String` | String de tamanho variável |
| `dataRegistro` | `LocalDate` | Data |
| `caracteristicas` | `List<String>` | Lista de valores; no CSV usa `|` como separador |
| `ano` | `int` | Valor inteiro |

Exemplo de registro:

```text
Carro{id=1, codigo='CAR00001', nome='chevrolet chevelle malibu',
dataRegistro=1970-01-01, caracteristicas=[origem-eua, 8-cilindros], ano=1970}
```

## Serialização
`Carro.toByteArray()` usa `ByteArrayOutputStream` e `DataOutputStream`.

`Carro.fromByteArray()` usa `ByteArrayInputStream` e `DataInputStream`.

Ordem dos dados serializados:

1. `int id`
2. `8 bytes codigo`
3. `UTF nome`
4. `long dataRegistro` usando `LocalDate.toEpochDay()`
5. `int quantidadeCaracteristicas`
6. cada característica como `UTF`
7. `int ano`

## Estrutura do arquivo binário
O arquivo principal é `data/dados.db`.

```text
[int ultimoId]
[byte lapide][int tamanhoRegistro][byte[] dadosObjeto]
[byte lapide][int tamanhoRegistro][byte[] dadosObjeto]
...
```

Convenção de lápide:

- `0` = registro ativo
- `1` = registro excluído

O cabeçalho guarda o maior ID já utilizado. IDs apagados não são reutilizados.

## Estrutura do projeto

```text
TP1-AEDS3-GRUPO15-CARROS/
├── src/
│   ├── Main.java
│   ├── TesteTP1.java
│   ├── model/
│   │   └── Carro.java
│   ├── dao/
│   │   └── ArquivoSequencial.java
│   ├── service/
│   │   ├── Importador.java
│   │   └── OrdenacaoExterna.java
│   └── util/
│       └── CsvUtil.java
├── data/
│   ├── base.csv
│   └── dados.db
├── temp/
├── compilar.bat
├── executar.bat
├── testar.bat
├── RESULTADO_TESTES.txt
└── README.md
```

## Funcionalidades
Menu por terminal com:

1. Carregar base de dados
2. Criar registro
3. Ler registro
4. Atualizar registro
5. Excluir registro
6. Ordenação externa
7. Listar registros ativos
8. Visualizar estrutura física do arquivo
0. Sair

## CRUD

### Create
- lê `ultimoId` do cabeçalho;
- incrementa o ID;
- gera o código fixo, por exemplo `CAR00061`;
- atualiza o cabeçalho;
- serializa o carro;
- grava no fim do arquivo com lápide ativa.

### Read
A busca é sequencial. O programa percorre o arquivo desde o primeiro registro, ignora registros apagados e retorna o carro ativo com o ID solicitado.

### Update
Existem dois casos obrigatórios:

**Mesmo tamanho:** os novos bytes substituem os antigos no mesmo endereço físico.

**Tamanho diferente:** a versão antiga recebe lápide `1` e a nova versão é gravada no final do arquivo, preservando o mesmo ID.

### Delete
A exclusão é lógica. Somente a lápide é alterada para `1`; os bytes permanecem fisicamente no arquivo até a ordenação/compactação.

## Ordenação externa
A chave de ordenação é o `id`.

O método recebe:

- número de caminhos;
- máximo de registros permitidos em memória.

### Distribuição
1. lê no máximo `N` registros ativos por vez;
2. ignora lápides;
3. ordena apenas o bloco carregado em memória;
4. grava um run temporário ordenado;
5. distribui os runs entre os caminhos temporários.

O arquivo inteiro nunca é carregado de uma vez na RAM.

### Intercalação
Os runs são intercalados em grupos limitados pelo número de caminhos informado. Uma `PriorityQueue` mantém o menor ID disponível entre os arquivos abertos.

São realizadas quantas passagens forem necessárias até restar um único run ordenado.

### Compactação
Ao final, é criado um novo `dados.db` contendo somente registros ativos em ordem crescente de ID. Assim, a ordenação remove fisicamente:

- registros apagados;
- versões antigas de updates;
- espaços deixados pelas lápides.

Depois da substituição, todo CRUD continua usando `data/dados.db`, agora ordenado e compactado.

## Inspeção física
A opção 8 mostra:

```text
Cabeçalho: últimoId = X

Posição: 4
Lápide: 0 (ativo)
Tamanho: ...
ID: 1
```

Isso facilita demonstrar atualizações, exclusões e compactação ao professor.

## Como compilar — Windows
Na pasta raiz do projeto:

```bat
compilar.bat
```

Ou manualmente:

```bat
javac -encoding UTF-8 -d out src\Main.java src\TesteTP1.java src\model\Carro.java src\dao\ArquivoSequencial.java src\service\Importador.java src\service\OrdenacaoExterna.java src\util\CsvUtil.java
```

## Como executar

```bat
executar.bat
```

Ou:

```bat
java -cp out Main
```

## Como executar os testes

```bat
testar.bat
```

Ou:

```bat
java -cp out TesteTP1
```

## Testes realizados
Foram executados testes de integração para:

1. carga CSV → binário;
2. Create;
3. Read de ID existente, inexistente e apagado;
4. Update com mesmo tamanho;
5. Update com tamanho diferente;
6. Delete;
7. ordenação externa e remoção física das lápides;
8. CRUD depois da ordenação.

No teste da ordenação foram usados:

- **4 caminhos**;
- **máximo de 7 registros em memória**.

Isso força a criação de vários blocos e comprova que a implementação é realmente externa.

## Resultado dos testes
Todos os testes obrigatórios passaram.

Resultado da ordenação de teste:

```text
registros=59, blocos iniciais=9, passagens de intercalação=2
```

## Decisões de implementação
- Projeto sem framework externo.
- CRUD feito diretamente sobre `RandomAccessFile`.
- Nenhum banco SQL ou JSON é usado como armazenamento principal.
- IDs não são reutilizados.
- A exclusão é lógica por lápide.
- A ordenação externa não carrega o arquivo inteiro na memória.
- O código foi mantido simples e dividido em entidade, DAO, importação e ordenação para facilitar a explicação em apresentação.
- Não foram implementadas etapas futuras como Árvore B+, Hash, Lista Invertida, Huffman, LZW, casamento de padrões ou criptografia.

## Identificação da entrega
**Grupo 15 — Tema: Carros**

**Gabriel Benicio Fonseca**  
**Rhayner Martins**
