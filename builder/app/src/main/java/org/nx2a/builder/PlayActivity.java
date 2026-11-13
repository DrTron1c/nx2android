package org.nx2a.builder;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** A home-screen icon's game (Engine.pin): starts it on the Switch 2 engine and draws nothing; without the engine, the builder opens to install it. */
public final class PlayActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String game = getIntent().getStringExtra("game");
        try {
            if (game != null && Engine.installed(this) > 0) startActivity(Engine.play(game));
            else {
                Toast.makeText(this, "Install the Switch 2 engine first: Play on the Switch 2 engine, in this app.", Toast.LENGTH_LONG).show();
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } catch (RuntimeException e) {
            Toast.makeText(this, "The game did not start: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
