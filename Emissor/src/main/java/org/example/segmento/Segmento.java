package org.example.segmento;

import java.nio.ByteBuffer;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Segmento {

    private byte tipo;
    private int num_seq;
    private int num_ack;
    private short tamanho_dados;
    private byte[] dados;

    // Construtor
    public Segmento(byte tipo, int num_seq, int num_ack, short tamanho_dados, byte[] dados) {
        this.tipo = tipo;
        this.num_seq = num_seq;
        this.num_ack = num_ack;
        this.tamanho_dados = tamanho_dados;
        this.dados = dados;
    }

    //Converter segmento para bytes
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        buffer.put(this.tipo);
        buffer.putInt(this.num_seq);
        buffer.putInt(this.num_ack);
        buffer.putShort(this.tamanho_dados);
        buffer.put(this.dados);
        return buffer.array();
    }

    //Converter bytes para segmento
    public static Segmento fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        byte tipo = buffer.get();
        int num_seq = buffer.getInt();
        int num_ack = buffer.getInt();
        short tamanho_dados = buffer.getShort();
        byte[] dados = new byte[tamanho_dados];
        buffer.get(dados);
        return new Segmento(tipo, num_seq, num_ack, tamanho_dados, dados);
    }
    
    //print bytes
    public void printBytes() {
        System.out.println("Tipo: " + this.tipo);
        System.out.println("Num_seq: " + this.num_seq);
        System.out.println("Num_ack: " + this.num_ack);
        System.out.println("Tamanho_dados: " + this.tamanho_dados);
        System.out.println("Dados: " + new String(this.dados));
    }
}
