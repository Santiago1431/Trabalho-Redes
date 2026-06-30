package org.example.receptor;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import org.example.segmento.Segmento;

public class SimuladorEmissor {

    public static void main(String[] args) {
        System.out.println("=== INICIANDO SIMULADOR EMISSOR DE TESTE ===");

        // 1. Iniciar o Receptor em uma thread separada (background)
        Thread receptorThread = new Thread(() -> {
            Receptor.main(new String[]{"5000"});
        });
        receptorThread.setDaemon(true);
        receptorThread.start();

        // Aguardar o Socket do Receptor inicializar
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        String caminhoDestino = "target/destino_teste.bin";
        
        // Limpar arquivo de teste anterior se existir
        try {
            Files.deleteIfExists(Paths.get(caminhoDestino));
            new File("target").mkdirs();
        } catch (IOException e) {
            System.err.println("Erro ao limpar ambiente de teste: " + e.getMessage());
        }

        try (DatagramSocket socketEmissor = new DatagramSocket()) {
            // Timeout de 1000ms para aguardar ACKs
            socketEmissor.setSoTimeout(1000);
            
            InetAddress IP_DESTINO = InetAddress.getByName("127.0.0.1");
            int PORTA_DESTINO = 5000;

            // 2. Criar os dados de teste (5000 bytes de padrão repetitivo)
            byte[] dadosOrigem = new byte[5000];
            for (int i = 0; i < dadosOrigem.length; i++) {
                dadosOrigem[i] = (byte) (i % 256);
            }

            // 3. FASE 1: Realizar Handshake
            // Formato payload: "prob_perda;tamanho_arquivo;caminho_destino"
            // Vamos configurar 15% de probabilidade de perda (0.15)
            double probPerda = 0.40;
            String paramsHandshake = probPerda + ";5000;" + caminhoDestino;
            byte[] bytesPayloadHandshake = paramsHandshake.getBytes();
            
            Segmento segHandshake = new Segmento(
                (byte) 2, // 2 = HANDSHAKE
                0,
                -1,
                (short) bytesPayloadHandshake.length,
                bytesPayloadHandshake
            );
            
            byte[] bytesHandshake = segHandshake.toBytes();
            DatagramPacket packHandshake = new DatagramPacket(bytesHandshake, bytesHandshake.length, IP_DESTINO, PORTA_DESTINO);
            
            System.out.println("[Emissor] Enviando pacote de HANDSHAKE...");
            byte[] bufferResposta = new byte[1024];
            DatagramPacket packResposta = new DatagramPacket(bufferResposta, bufferResposta.length);
            
            while (true) {
                socketEmissor.send(packHandshake);
                try {
                    socketEmissor.receive(packResposta);
                    Segmento resposta = Segmento.fromBytes(packResposta.getData());
                    if (resposta.getTipo() == 1 && resposta.getNum_ack() == -1) {
                        System.out.println("[Emissor] Handshake confirmado pelo Receptor (ACK -1 recebido).");
                        break;
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("[Emissor] Timeout aguardando ACK de Handshake. Reenviando...");
                }
            }

            // 4. FASE 2: Envio de Dados com Janela Deslizante (Go-Back-N)
            int tamanhoJanela = 3;
            int base = 0;
            int nextseqnum = 0;
            int tamanhoPayload = 1000; // 5 segmentos de 1000 bytes
            int totalPacotes = 5;

            System.out.println("[Emissor] Iniciando envio dos dados GBN (Janela: " + tamanhoJanela + ", Perda Simulada: " + (probPerda * 100) + "%)...");

            while (base < totalPacotes) {
                // Envia pacotes enquanto a janela não estiver cheia
                while (nextseqnum < base + tamanhoJanela && nextseqnum < totalPacotes) {
                    int offset = nextseqnum * tamanhoPayload;
                    byte[] dadosPacote = Arrays.copyOfRange(dadosOrigem, offset, offset + tamanhoPayload);
                    
                    Segmento segDados = new Segmento(
                        (byte) 0, // 0 = DATA
                        nextseqnum,
                        -1,
                        (short) dadosPacote.length,
                        dadosPacote
                    );
                    
                    byte[] bytesSegmento = segDados.toBytes();
                    DatagramPacket packDados = new DatagramPacket(bytesSegmento, bytesSegmento.length, IP_DESTINO, PORTA_DESTINO);
                    
                    socketEmissor.send(packDados);
                    System.out.printf("[Emissor] Enviado Pacote %d (bytes %d a %d)\n", nextseqnum, offset, offset + dadosPacote.length - 1);
                    nextseqnum++;
                }

                // Tenta receber ACK
                try {
                    socketEmissor.receive(packResposta);
                    Segmento resposta = Segmento.fromBytes(packResposta.getData());
                    if (resposta.getTipo() == 1) { // 1 = ACK
                        int ack = resposta.getNum_ack();
                        System.out.println("[Emissor] Recebido ACK cumulativo: " + ack);
                        if (ack >= base) {
                            base = ack + 1; // Avança a base da janela
                            System.out.println("[Emissor] Janela avançada. Nova base: " + base);
                        }
                    }
                } catch (SocketTimeoutException e) {
                    // Timeout disparado: recua para a base (Go-Back-N)
                    System.out.println("[Emissor] TIMEOUT! Retransmitindo janela a partir do pacote " + base);
                    nextseqnum = base;
                }
            }

            System.out.println("[Emissor] Todos os pacotes de dados confirmados.");

            // 5. FASE 3: Envio de pacote FIN
            Segmento segFin = new Segmento(
                (byte) 3, // 3 = FIN
                totalPacotes,
                -1,
                (short) 0,
                new byte[0]
            );
            byte[] bytesFin = segFin.toBytes();
            DatagramPacket packFin = new DatagramPacket(bytesFin, bytesFin.length, IP_DESTINO, PORTA_DESTINO);

            System.out.println("[Emissor] Enviando pacote FIN...");
            while (true) {
                socketEmissor.send(packFin);
                try {
                    socketEmissor.receive(packResposta);
                    Segmento resposta = Segmento.fromBytes(packResposta.getData());
                    if (resposta.getTipo() == 1 && resposta.getNum_ack() == totalPacotes) {
                        System.out.println("[Emissor] ACK de FIN recebido. Transferência encerrada.");
                        break;
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("[Emissor] Timeout aguardando ACK de FIN. Reenviando FIN...");
                }
            }

            // 6. FASE 4: Verificar integridade do arquivo gerado
            System.out.println("[Emissor] Aguardando gravação de arquivo pelo Receptor...");
            Thread.sleep(1000); // Aguarda gravação final pelo receptor

            byte[] dadosDestino = Files.readAllBytes(Paths.get(caminhoDestino));
            if (Arrays.equals(dadosOrigem, dadosDestino)) {
                System.out.println("\n🎉 [SUCESSO] O arquivo de destino foi reconstruído perfeitamente!");
                System.out.println("Tamanho origem: " + dadosOrigem.length + " bytes | Tamanho destino: " + dadosDestino.length + " bytes");
            } else {
                System.out.println("\n❌ [FALHA] O arquivo de destino é diferente do arquivo de origem.");
            }

        } catch (Exception e) {
            System.err.println("Erro na execução do simulador: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
