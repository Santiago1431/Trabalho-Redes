package org.example.receptor;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Random;
import org.example.segmento.Segmento;

public class Receptor {
    
    //Porta padrão 
    private static final int PORTA_PADRAO = 5000;
    
    public static void main(String[] args) {
        int porta = PORTA_PADRAO;
        
        //Passando porta como argumento
        if (args.length > 0) {
            try {
                porta = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Porta inválida fornecida como argumento. Usando a porta padrão: " + PORTA_PADRAO);
            }
        }
        
        System.out.println("Receptor configurado para escutar na porta: " + porta);

        conexaoUDP(porta);

    }

    private static void conexaoUDP(int porta) {
        //Inicializando socket UDP
        try {
            DatagramSocket socket = new DatagramSocket(porta);
            System.out.println("Socket UDP inicializado com sucesso na porta: " + porta);
            
            // Buffer reutilizável para receber os pacotes UDP
            byte[] bufferRecepcao = new byte[1024];
            DatagramPacket pacote = new DatagramPacket(bufferRecepcao, bufferRecepcao.length);
            
            System.out.println("Aguardando pacote de Handshake...");
            Segmento segmentoHandshake = null;
            while (true) {
                socket.receive(pacote);
                segmentoHandshake = Segmento.fromBytes(pacote.getData());
                if (segmentoHandshake.getTipo() == 2) { // 2 = HANDSHAKE
                    System.out.println("Pacote de Handshake recebido do emissor!");
                    break;
                }
                System.out.println("Pacote recebido ignorado (esperando HANDSHAKE). Tipo: " + segmentoHandshake.getTipo());
            }
            
            // Processar os parâmetros da sessão
            String payloadHandshake = new String(segmentoHandshake.getDados(), 0, segmentoHandshake.getTamanho_dados());
            String[] partes = payloadHandshake.split(";");
            if (partes.length < 3) {
                System.err.println("Erro: Dados do handshake inválidos: " + payloadHandshake);
                return;
            }
            
            double p = Double.parseDouble(partes[0]);
            long tamanhoArquivo = Long.parseLong(partes[1]);
            String caminhoDestino = partes[2];
            
            System.out.println("Parâmetros da sessão estabelecidos:");
            System.out.println(" - Probabilidade de perda (p): " + p);
            System.out.println(" - Tamanho total do arquivo: " + tamanhoArquivo + " bytes");
            System.out.println(" - Caminho de destino: " + caminhoDestino);
            
            // Preparar ambiente de gravação
            java.io.File arquivoDestino = new java.io.File(caminhoDestino);
            java.io.File pastaPai = arquivoDestino.getParentFile();
            if (pastaPai != null && !pastaPai.exists()) {
                pastaPai.mkdirs();
            }
            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(arquivoDestino));
            
            int expectedseqnum = 0;
            int totalPacotesRecebidos = 0;
            int totalPacotesDescartadosPorPerda = 0;
            
            System.out.println("Ambiente de gravação pronto. Arquivo: " + caminhoDestino);

            // Confirmar o Handshake
            InetAddress ipEmissor = pacote.getAddress();
            int portaEmissor = pacote.getPort();
            
            Segmento ackHandshake = new Segmento((byte) 1, 0, -1, (short) 0, new byte[0]);
            byte[] ackBytes = ackHandshake.toBytes();
            DatagramPacket pacoteAck = new DatagramPacket(ackBytes, ackBytes.length, ipEmissor, portaEmissor);
            socket.send(pacoteAck);
            System.out.println("ACK de Handshake enviado para " + ipEmissor + ":" + portaEmissor);
            
            System.out.println("Iniciando a recepção dos dados...");
            Random random = new Random();
            
            while (true) {
                socket.receive(pacote);
                Segmento segmento = Segmento.fromBytes(pacote.getData());
                
                if (segmento.getTipo() == 0) { // 0 = DATA
                    int seqNum = segmento.getNum_seq();
                    
                    if (seqNum == expectedseqnum) {
                        // Simular perda
                        double r = random.nextDouble();
                        if (r < p) {
                            System.out.printf("PACOTE %d DESCARTADO (Simulação de perda, r = %.4f < p = %.4f)\n", seqNum, r, p);
                            totalPacotesDescartadosPorPerda++;
                        } else {
                            // Gravar dados no arquivo
                            bos.write(segmento.getDados(), 0, segmento.getTamanho_dados());
                            
                            // Enviar ACK do pacote recebido
                            Segmento ack = new Segmento((byte) 1, 0, expectedseqnum, (short) 0, new byte[0]);
                            byte[] ackBytesSend = ack.toBytes();
                            DatagramPacket pacoteAckSend = new DatagramPacket(ackBytesSend, ackBytesSend.length, ipEmissor, portaEmissor);
                            socket.send(pacoteAckSend);
                            
                            System.out.printf("PACOTE %d recebido, gravado e ACK %d enviado.\n", seqNum, expectedseqnum);
                            
                            expectedseqnum++;
                            totalPacotesRecebidos++;
                        }
                    } else {
                        System.out.printf("PACOTE %d descartado (Fora de ordem, esperado: %d).\n", seqNum, expectedseqnum);
                        
                        // Reenviar ACK do último pacote correto (expectedseqnum - 1)
                        int ultimoAck = expectedseqnum - 1;
                        Segmento ack = new Segmento((byte) 1, 0, ultimoAck, (short) 0, new byte[0]);
                        byte[] ackBytesSend = ack.toBytes();
                        DatagramPacket pacoteAckSend = new DatagramPacket(ackBytesSend, ackBytesSend.length, ipEmissor, portaEmissor);
                        socket.send(pacoteAckSend);
                        
                        System.out.printf("ACK cumulativo %d reenviado.\n", ultimoAck);
                    }
                } else if (segmento.getTipo() == 3) { // 3 = FIN
                    System.out.println("Pacote FIN recebido do emissor!");
                    
                    // Enviar ACK do FIN
                    Segmento ackFin = new Segmento((byte) 1, 0, segmento.getNum_seq(), (short) 0, new byte[0]);
                    byte[] ackBytesSend = ackFin.toBytes();
                    DatagramPacket pacoteAckSend = new DatagramPacket(ackBytesSend, ackBytesSend.length, ipEmissor, portaEmissor);
                    socket.send(pacoteAckSend);
                    System.out.println("ACK de FIN enviado. Encerrando transferência...");
                    break;
                }
            }
            
            bos.flush();
            bos.close();
            System.out.println("Arquivo gravado e fechado com sucesso!");
            
            // Exibir estatísticas
            int totalPacotes = totalPacotesRecebidos + totalPacotesDescartadosPorPerda;
            double taxaPerdaEfetiva = totalPacotes > 0 
                ? (double) totalPacotesDescartadosPorPerda / totalPacotes * 100 
                : 0.0;
                
            System.out.println("\n=== Estatísticas da Transferência ===");
            System.out.println("Total de pacotes de dados processados: " + totalPacotes);
            System.out.println("Pacotes recebidos com sucesso: " + totalPacotesRecebidos);
            System.out.println("Pacotes descartados por perda simulada: " + totalPacotesDescartadosPorPerda);
            System.out.printf("Taxa de perda efetiva: %.2f%%\n", taxaPerdaEfetiva);
            System.out.printf("Taxa de perda configurada (p): %.2f%%\n", p * 100);
            
            // Calcular e exibir Hash MD5
            System.out.println("\nVerificando integridade do arquivo...");
            String hashMD5 = calcularMD5(caminhoDestino);
            System.out.println("Hash MD5 do arquivo recebido: " + hashMD5);
            System.out.println("=====================================\n");
            
        } catch (java.net.SocketException e) {
            System.err.println("Erro ao inicializar o socket UDP na porta " + porta + ": " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Erro de E/S na recepção de pacotes: " + e.getMessage());
        }
    }

    private static String calcularMD5(String caminhoArquivo) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (InputStream is = Files.newInputStream(Paths.get(caminhoArquivo))) {
                byte[] buffer = new byte[8192];
                int lido;
                while ((lido = is.read(buffer)) != -1) {
                    md.update(buffer, 0, lido);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "Não foi possível calcular o hash MD5: " + e.getMessage();
        }
    }
}

