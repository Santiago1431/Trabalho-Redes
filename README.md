# Trabalho Prático: Implementação do Protocolo Go-Back-N sobre UDP

Este repositório contém a implementação prática de um protocolo de transferência confiável de dados utilizando o algoritmo **Go-Back-N (GBN)** construído diretamente sobre a camada de transporte **UDP** (não confiável). 

O projeto foi desenvolvido para a disciplina de **Redes de Computadores ** do curso de Ciência da Computação da **Universidade Federal de Alfena (UNIFAL)**.

## Integrantes do Grupo
* **Eurico Santiago Climaco Rodrigues**
* **Diogo Peixoto Rossini**

---

## Estrutura do Projeto

O projeto é dividido em dois módulos Maven principais escritos em Java:

* **Emissor (Sender):** Lê um arquivo local, segmenta-o em pacotes customizados, envia-os usando uma janela deslizante e gerencia o temporizador e retransmissões do GBN.
* **Receptor (Receiver):** Escuta em uma porta UDP, recebe os pacotes, simula perda probabilística de dados, valida a ordem, grava os dados recebidos em disco e envia confirmações (ACKs) cumulativas.
* **Segmento:** Classe compartilhada que define o formato do cabeçalho de 11 bytes e serialização/deserialização dos pacotes.

---

## Pré-requisitos

Para compilar e executar o projeto, você precisará de:
* **Java Development Kit (JDK) 17** ou superior.
* **Apache Maven** instalado.
---

## Compilação dos Módulos

Ambos os módulos utilizam Maven para gerenciamento de dependências e compilação. Para compilar os dois projetos de uma vez, execute os seguintes comandos no diretório raiz do projeto:

```bash
# Compilar o Emissor
mvn -f Emissor/pom.xml clean compile

# Compilar o Receptor
mvn -f Receptor/pom.xml clean compile
```

---

## Execução Manual

### 1. Iniciar o Receptor
O Receptor deve ser iniciado primeiro para ficar aguardando a conexão (handshake) do Emissor. Você deve fornecer a porta UDP a ser utilizada como argumento opcional (a porta padrão é `5000` caso não especificada):

```bash
java -cp Receptor/target/classes org.example.receptor.Receptor [porta]
```
*Exemplo:* Escutar na porta `5000`:
```bash
java -cp Receptor/target/classes org.example.receptor.Receptor 5000
```

### 2. Iniciar o Emissor
O Emissor é executado passando os parâmetros de configuração na linha de comando:

```bash
java -cp Emissor/target/classes org.example.segmento.emissor.Emissor <caminho_arquivo_origem> <ip_destino>:<porta_destino>:<caminho_destino_receptor> <N> <p>
```

#### Parâmetros:
* `<caminho_arquivo_origem>`: Caminho do arquivo local a ser enviado (ex: `origem_1mb.bin`).
* `<ip_destino>:<porta_destino>:<caminho_destino_receptor>`: Uma única string combinando o endereço IP do Receptor, a porta configurada e o caminho onde o Receptor deve gravar o arquivo final, separados por dois pontos (`:`).
* `<N>`: Tamanho da janela deslizante do Go-Back-N (ex: `8`).
* `<p>`: Probabilidade nominal de perda de pacotes simulada no receptor, expressa como número decimal entre `0.0` e `1.0` (ex: `0.10` para 10% de perda).

*Exemplo:* Enviar o arquivo `origem_200k.bin` para o receptor local rodando na porta `5000`, gravando como `destino.bin`, usando janela $N=8$ e probabilidade de perda de $5\%$ ($p=0.05$):
```bash
java -cp Emissor/target/classes org.example.segmento.emissor.Emissor origem_200k.bin 127.0.0.1:5000:target/destino.bin 8 0.05
```


## Resumo dos Resultados de Desempenho

Os experimentos confirmam o comportamento teórico esperado para o protocolo Go-Back-N em redes locais:
* **Variação de Janela (N):** Para janelas pequenas ($N=1$), a rede opera como *Stop-and-Wait*, sofrendo com baixo aproveitamento de link. Aumentar a janela para $N=4$ e $N=8$ otimiza o tempo. No entanto, para janelas grandes ($N=32$) sob perda síncrona de $10\%$, o tráfego de retransmissões cresce exponencialmente (mais de 500 retransmissões redundantes) devido ao descarte de pacotes fora de ordem pelo Receptor, gerando retransmissões em massa ao estourar o temporizador.
* **Variação de Perda (p):** O desempenho degrada severamente com taxas de perda maiores que $10\%$. O link passa a sofrer constantes esperas ociosas devido a timeouts (configurados em $300\text{ ms}$) no Emissor, seguidos por retransmissões em massa de janelas inteiras.
