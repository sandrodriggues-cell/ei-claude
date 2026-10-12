package com.eiclaude.app;

import com.sun.jna.Native;
import com.sun.jna.Pointer;

import org.vosk.Model;

/**
 * Ligação extra com a API em C do Vosk: vosk_model_find_word, que a biblioteca Java
 * não expõe. Serve para saber se o modelo conhece uma palavra antes de usá-la na frase.
 */
final class VoskExtras {

    private static final boolean AVAILABLE;

    static {
        boolean ok;
        try {
            Native.register(VoskExtras.class, "vosk");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        AVAILABLE = ok;
    }

    private VoskExtras() {
    }

    static native int vosk_model_find_word(Pointer model, String word);

    /** 1 = o modelo conhece a palavra, 0 = não conhece, -1 = não foi possível conferir. */
    static int knows(Model model, String word) {
        if (!AVAILABLE || model == null || word == null || word.isEmpty()) return -1;
        try {
            return vosk_model_find_word(model.getPointer(), word) >= 0 ? 1 : 0;
        } catch (Throwable t) {
            return -1;
        }
    }
}
