package com.wbeam;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button start = findViewById(R.id.startButton);
        Button stop = findViewById(R.id.stopButton);

        start.setOnClickListener(v -> {
            Intent intent = new Intent(this, StreamService.class);
            intent.setAction(StreamService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        });

        stop.setOnClickListener(v -> {
            Intent intent = new Intent(this, StreamService.class);
            intent.setAction(StreamService.ACTION_STOP);
            startService(intent);
        });
    }
}
