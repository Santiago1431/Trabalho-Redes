package org.example.segmento.emissor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.example.segmento.Segmento;

public class Emissor {
    
    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("Uso correto: java Emissor <arquivo_origem> <IP_destino>:<porta_destino> <tamanho_janela> <prob_perda>");
            System.err.println("Também aceita: java Emissor <arquivo_origem> <IP_destino>:<porta_destino>:<caminho_destino> <tamanho_janela> <prob_perda>");
            System.exit(1);
        }
        
        String caminhoOrigem = args[0];
        String destino = args[1];
        String tamanhoJanelaStr = args[2];
        String probPerdaStr = args[3];
        
        // 1. Validar e tratar o arquivo de origem
        File arquivoOrigem = new File(caminhoOrigem);
        if (!arquivoOrigem.exists() || !arquivoOrigem.isFile() || !arquivoOrigem.canRead()) {
            System.err.println("Erro: O arquivo de origem não existe ou não pode ser lido: " + caminhoOrigem);
            System.exit(1);
        }
        
        // 2. Fazer o parse do endereço de destino (suporta IP:porta, IP:porta:caminho, ou IP:caminho)
        String ipStr = "";
        int portaDestino = 5000; // Padrão
        String caminhoDestino = "/tmp/" + arquivoOrigem.getName(); // Padrão
        
        String[] partesDestino = destino.split(":", -1);
        if (partesDestino.length >= 3) {
            ipStr = partesDestino[0];
            try {
                portaDestino = Integer.parseInt(partesDestino[1]);
            } catch (NumberFormatException e) {
                System.err.println("Porta inválida fornecida. Usando a porta padrão: " + portaDestino);
            }
            StringBuilder sb = new StringBuilder(partesDestino[2]);
            for (int i = 3; i < partesDestino.length; i++) {
                sb.append(":").append(partesDestino[i]);
            }
            caminhoDestino = sb.toString();
        } else if (partesDestino.length == 2) {
            ipStr = partesDestino[0];
            if (partesDestino[1].matches("\\d+")) {
                portaDestino = Integer.parseInt(partesDestino[1]);
            } else {
                caminhoDestino = partesDestino[1];
            }
        } else {
            ipStr = destino;
        }
        
        InetAddress ipDestino = null;
        try {
            ipDestino = InetAddress.getByName(ipStr);
        } catch (UnknownHostException e) {
            System.err.println("Erro: Host de destino desconhecido ou inválido: " + ipStr);
            System.exit(1);
        }
        
        // 3. Validar o tamanho da janela N
        int N = -1;
        try {
            N = Integer.parseInt(tamanhoJanelaStr);
            if (N <= 0) {
                System.err.println("Erro: O tamanho da janela N deve ser um inteiro maior que 0.");
                System.exit(1);
            }
        } catch (NumberFormatException e) {
            System.err.println("Erro: O tamanho da janela N fornecido é inválido: " + tamanhoJanelaStr);
            System.exit(1);
        }
        
        // 4. Validar a probabilidade de perda p
        double p = -1.0;
        try {
            p = Double.parseDouble(probPerdaStr);
            if (p < 0.0 || p > 1.0) {
                System.err.println("Erro: A probabilidade de perda p deve ser um valor real entre 0.0 e 1.0.");
                System.exit(1);
            }
        } catch (NumberFormatException e) {
            System.err.println("Erro: A probabilidade de perda p fornecida é inválida: " + probPerdaStr);
            System.exit(1);
        }
        
        System.out.println("=== Parâmetros do Emissor ===");
        System.out.println(" - Arquivo de origem: " + arquivoOrigem.getAbsolutePath() + " (" + arquivoOrigem.length() + " bytes)");
        System.out.println(" - IP Destino: " + ipDestino.getHostAddress());
        System.out.println(" - Porta Destino: " + portaDestino);
        System.out.println(" - Caminho Destino no Receptor: " + caminhoDestino);
        System.out.println(" - Tamanho da Janela (N): " + N);
        System.out.println(" - Probabilidade de perda (p): " + p);
        System.out.println("=============================");
        
        // 5. Inicializar o DatagramSocket
        try (DatagramSocket socket = new DatagramSocket()) {
            System.out.println("Socket UDP inicializado com sucesso.");
            
            // --- FASE 1: Conexão e Handshake ---
            String payloadHandshake = p + ";" + arquivoOrigem.length() + ";" + caminhoDestino;
            byte[] payloadBytes = payloadHandshake.getBytes(StandardCharsets.UTF_8);
            
            if (payloadBytes.length > 1013) {
                System.err.println("Erro: Payload do handshake muito grande.");
                System.exit(1);
            }
            
            Segmento segmentoHandshake = new Segmento((byte) 2, 0, -1, (short) payloadBytes.length, payloadBytes);
            byte[] bytesHandshake = segmentoHandshake.toBytes();
            DatagramPacket pacoteHandshake = new DatagramPacket(bytesHandshake, bytesHandshake.length, ipDestino, portaDestino);
            
            byte[] bufferAck = new byte[1024];
            DatagramPacket pacoteAck = new DatagramPacket(bufferAck, bufferAck.length);
            
            boolean handshakeConfirmado = false;
            int tentativasHandshake = 0;
            
            socket.setSoTimeout(1000); // Timeout de 1 segundo para o handshake
            
            System.out.println("\nIniciando Handshake com o Receptor...");
            while (!handshakeConfirmado && tentativasHandshake < 10) {
                try {
                    tentativasHandshake++;
                    System.out.println("Enviando pacote de HANDSHAKE (Tentativa " + tentativasHandshake + ")...");
                    socket.send(pacoteHandshake);
                    
                    socket.receive(pacoteAck);
                    Segmento ack = Segmento.fromBytes(pacoteAck.getData());
                    
                    if (ack.getTipo() == 1 && ack.getNum_ack() == -1) {
                        System.out.println("Handshake confirmado pelo Receptor!");
                        handshakeConfirmado = true;
                    } else {
                        System.out.println("Recebido pacote inesperado durante o handshake. Tipo: " + ack.getTipo() + ", ACK: " + ack.getNum_ack());
                    }
                } catch (java.io.InterruptedIOException e) {
                    System.out.println("Timeout no Handshake. Reenviando...");
                }
            }
            
            if (!handshakeConfirmado) {
                System.err.println("Erro: Não foi possível estabelecer conexão com o Receptor após 10 tentativas.");
                System.exit(1);
            }
            
            // --- FASE 2: Envio de Dados com Janela Deslizante (GBN) ---
            System.out.println("\nPreparando segmentação do arquivo...");
            List<Segmento> pacotes = new ArrayList<>();
            try (FileInputStream fis = new FileInputStream(arquivoOrigem)) {
                byte[] fileBuffer = new byte[1013];
                int bytesRead;
                int seqNum = 0;
                while ((bytesRead = fis.read(fileBuffer)) != -1) {
                    byte[] chunk = new byte[bytesRead];
                    System.arraycopy(fileBuffer, 0, chunk, 0, bytesRead);
                    Segmento seg = new Segmento((byte) 0, seqNum, -1, (short) bytesRead, chunk);
                    pacotes.add(seg);
                    seqNum++;
                }
            }
            
            int totalPacotes = pacotes.size();
            System.out.println("Arquivo segmentado em " + totalPacotes + " pacotes.");
            
            // Variáveis de controle GBN
            int base = 0;
            int nextseqnum = 0;
            long timerStart = -1;
            long timeoutLimit = 300; // 300 ms
            long inicioTransferencia = System.currentTimeMillis();
            int totalRetransmissoes = 0;
            
            socket.setSoTimeout(10); // Timeout baixo para ler ACKs de forma não bloqueante
            
            System.out.println("\nIniciando envio dos dados via Go-Back-N...");
            while (base < totalPacotes) {
                // Enviar pacotes dentro da janela
                while (nextseqnum < base + N && nextseqnum < totalPacotes) {
                    Segmento seg = pacotes.get(nextseqnum);
                    byte[] bytesSeg = seg.toBytes();
                    DatagramPacket pacoteSeg = new DatagramPacket(bytesSeg, bytesSeg.length, ipDestino, portaDestino);
                    
                    try {
                        socket.send(pacoteSeg);
                        System.out.println("[ENVIO] Pacote " + nextseqnum + " enviado.");
                        
                        if (base == nextseqnum) {
                            timerStart = System.currentTimeMillis();
                        }
                        nextseqnum++;
                    } catch (IOException e) {
                        System.err.println("Erro ao enviar pacote " + nextseqnum + ": " + e.getMessage());
                    }
                }
                
                // Receber ACKs pendentes
                while (true) {
                    try {
                        socket.receive(pacoteAck);
                        Segmento ack = Segmento.fromBytes(pacoteAck.getData());
                        
                        if (ack.getTipo() == 1) { // 1 = ACK
                            int numAck = ack.getNum_ack();
                            if (numAck >= base) {
                                base = numAck + 1;
                                System.out.println("[ACK] Recebido ACK cumulativo " + numAck + ". Avançando base para " + base + ".");
                                
                                if (base == nextseqnum) {
                                    timerStart = -1; // Janela vazia, desliga timer
                                } else {
                                    timerStart = System.currentTimeMillis(); // Reinicia timer para o pacote mais antigo em voo
                                }
                            }
                        }
                    } catch (java.io.InterruptedIOException e) {
                        // Sem mais ACKs pendentes por enquanto
                        break;
                    } catch (IOException e) {
                        System.err.println("Erro ao receber ACK: " + e.getMessage());
                        break;
                    }
                }
                
                // Verificar Timeout
                if (timerStart != -1 && (System.currentTimeMillis() - timerStart >= timeoutLimit)) {
                    System.out.println("[TIMEOUT] Expiração do temporizador para o pacote " + base + ". Retransmitindo janela...");
                    totalRetransmissoes += (nextseqnum - base);
                    nextseqnum = base; // Retorna o ponteiro para a base
                    timerStart = System.currentTimeMillis(); // Reinicia o timer para a retransmissão
                }
                
                // Pequena pausa para evitar sobrecarga excessiva de CPU
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            long fimTransferencia = System.currentTimeMillis();
            
            // --- FASE 3: Encerramento da Conexão (FIN) ---
            System.out.println("\nTodos os pacotes de dados foram confirmados. Iniciando encerramento (FIN)...");
            
            Segmento segmentoFin = new Segmento((byte) 3, totalPacotes, -1, (short) 0, new byte[0]);
            byte[] bytesFin = segmentoFin.toBytes();
            DatagramPacket pacoteFin = new DatagramPacket(bytesFin, bytesFin.length, ipDestino, portaDestino);
            
            boolean finConfirmado = false;
            int tentativasFin = 0;
            
            socket.setSoTimeout(1000); // 1 segundo de timeout para aguardar o ACK do FIN
            
            while (!finConfirmado && tentativasFin < 10) {
                try {
                    tentativasFin++;
                    System.out.println("Enviando pacote FIN (Tentativa " + tentativasFin + ")...");
                    socket.send(pacoteFin);
                    
                    socket.receive(pacoteAck);
                    Segmento ack = Segmento.fromBytes(pacoteAck.getData());
                    
                    if (ack.getTipo() == 1 && ack.getNum_ack() == totalPacotes) {
                        System.out.println("ACK do FIN recebido! Conexão encerrada com sucesso.");
                        finConfirmado = true;
                    }
                } catch (java.io.InterruptedIOException e) {
                    System.out.println("Timeout aguardando ACK do FIN. Reenviando...");
                } catch (IOException e) {
                    System.err.println("Erro ao enviar/receber durante o encerramento: " + e.getMessage());
                }
            }
            
            // --- FASE 4: Logs, Estatísticas e Integridade ---
            long tempoTotalMs = fimTransferencia - inicioTransferencia;
            double tempoTotalSegundos = tempoTotalMs / 1000.0;
            long totalBytesArquivo = arquivoOrigem.length();
            double throughputKBps = tempoTotalSegundos > 0 
                ? (totalBytesArquivo / 1024.0) / tempoTotalSegundos 
                : 0.0;
            
            System.out.println("\n=== Estatísticas do Emissor ===");
            System.out.println("Tempo total de transferência: " + tempoTotalSegundos + " segundos");
            System.out.println("Pacotes originais transmitidos: " + totalPacotes);
            System.out.println("Quantidade de retransmissões: " + totalRetransmissoes);
            System.out.printf("Throughput médio estimado: %.2f KB/s\n", throughputKBps);
            
            // Calcular e exibir Hash MD5
            System.out.println("\nCalculando integridade do arquivo local...");
            String hashMD5 = calcularMD5(arquivoOrigem);
            System.out.println("Hash MD5 do arquivo original: " + hashMD5);
            System.out.println("================================\n");
            
        } catch (SocketException e) {
            System.err.println("Erro de Socket: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Erro de E/S: " + e.getMessage());
            System.exit(1);
        }
    }
    
    private static String calcularMD5(File arquivo) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (InputStream is = Files.newInputStream(arquivo.toPath())) {
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
