# TP1 AEDS III — Carros

## Integrantes

- Gabriel Benicio Fonseca
- Rhayner Martins

## Base de dados

- Used Cars Dataset — Craigslist
- Fonte: [Kaggle — Austin Reese](https://www.kaggle.com/datasets/austinreese/craigslist-carstrucks-data)
- Arquivo utilizado: `data/base.csv`
- Base original: aproximadamente 426.880 anúncios
- Recorte utilizado: 100.000 registros

Os registros vieram de anúncios reais de veículos do Craigslist. O nome combina
fabricante e modelo, `dataRegistro` usa `posting_date` e as características do
veículo são reunidas em uma lista separada por `|`.

## Entidade Carro

| Campo | Tipo |
|---|---|
| id | int |
| codigo | String fixa de 12 bytes |
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
- Base de 100.000 registros: 100 runs iniciais e 4 passagens.
