package com.eiclaude.relogio;

/**
 * Um jeito de transformar a fala em texto. O relógio pode ter (ou não) um reconhecedor do
 * sistema; por isso há vários motores, tentados em ordem até um funcionar.
 * Todas as chamadas e retornos acontecem na thread principal.
 */
interface VoiceInput {

    interface Callback {
        /** Mensagem de preparo (ex.: "Preparando a voz offline…"). */
        void onStatus(String message);

        /** O microfone está aberto: pode falar. */
        void onReady();

        /** Texto parcial enquanto a pessoa fala. */
        void onPartial(String text);

        /** Fala final reconhecida. */
        void onText(String text);

        /** Ninguém falou (ou nada foi entendido). */
        void onNoSpeech();

        /** Erro passageiro (sem internet, microfone ocupado…). */
        void onError(String message);

        /** Este motor não funciona neste relógio: tentar o próximo. */
        void onUnavailable(String reason);
    }

    /** Identificador estável, para lembrar qual motor funcionou. */
    String id();

    /** Nome para mostrar nos ajustes. */
    String label();

    void start(Callback callback);

    /** Para de ouvir sem chamar o callback. */
    void cancel();

    /** Libera os recursos (ao fechar a tela). */
    void release();
}
