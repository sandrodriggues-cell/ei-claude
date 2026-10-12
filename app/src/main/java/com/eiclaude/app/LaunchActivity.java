package com.eiclaude.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

/** Tela invisível: acende a tela, pede o desbloqueio se for preciso e abre o Claude. */
public class LaunchActivity extends Activity {

    private boolean done;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    openClaude();
                }

                @Override
                public void onDismissCancelled() {
                    ClaudeAssistService.launchCancelled(LaunchActivity.this);
                    finishQuietly();
                }

                @Override
                public void onDismissError() {
                    ClaudeAssistService.launchCancelled(LaunchActivity.this);
                    finishQuietly();
                }
            });
        } else {
            openClaude();
        }
    }

    private void openClaude() {
        if (done) return;
        done = true;
        Intent launch = getPackageManager().getLaunchIntentForPackage(WakeService.CLAUDE_PACKAGE);
        if (launch == null) {
            Toast.makeText(this, "App do Claude não encontrado.", Toast.LENGTH_LONG).show();
        } else {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                startActivity(launch);
                // O prazo para achar os botões conta a partir daqui (o desbloqueio pode ter demorado).
                ClaudeAssistService.claudeLaunched();
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, "Não consegui abrir o Claude.", Toast.LENGTH_LONG).show();
                ClaudeAssistService.launchCancelled(this);
            }
        }
        finishQuietly();
    }

    private void finishQuietly() {
        finish();
        overridePendingTransition(0, 0);
    }
}
