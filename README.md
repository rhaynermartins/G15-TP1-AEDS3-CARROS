# TP1 AEDS III — Carros

## Integrantes

- Gabriel Benicio Fonseca
- Rhayner Martins

## Base de dados

- Auto MPG
- Fonte: [UCI Machine Learning Repository](https://archive.ics.uci.edu/dataset/9/auto)
- Arquivo utilizado: `data/base.csv`
- Recorte utilizado: 60 automóveis

A base foi adaptada para os campos usados pela entidade `Carro`. O CSV é
carregado no arquivo binário utilizado pelo CRUD.

## Entidade Carro

| Campo | Tipo |
|---|---|
| id | int |
| codigo | String fixa de 8 bytes |
| nome | String variável |
| dataRegistro | LocalDate |
| caracteristicas | List&lt;String&gt; |
| ano | int |

Separador utilizado na lista de características: `|`

## Arquivo binário

```text
[int ultimoId]

[byte lapide]
[int tamanhoRegistro]
[byte[] dadosObjeto]
```

Convenção:

```text
0 = ativo
1 = excluído
```

## Funcionalidades

- Carga do CSV para arquivo binário
- CRUD sequencial
  - Create
  - Read
  - Update
  - Delete
- Ordenação externa
  - Intercalação Balanceada
  - Seleção por Substituição

## Ordenação Externa

**Intercalação Balanceada:** os registros são divididos em runs limitados pela
memória, distribuídos entre os caminhos e intercalados até formar o arquivo final
ordenado.

**Seleção por Substituição:** os runs são gerados mantendo registros em memória e
congelando aqueles que pertencem ao próximo run. Depois, os runs passam pela
mesma intercalação balanceada.

## Testes

Foram testados:

- carga;
- CRUD;
- update com mesmo tamanho;
- update com tamanho diferente;
- exclusão por lápide;
- as duas estratégias de ordenação;
- CRUD após ordenação.

Resultados da ordenação:

- Intercalação Balanceada: 59 registros, 9 runs iniciais e 2 passagens.
- Seleção por Substituição: 17 registros, 2 runs iniciais e 1 passagem.
