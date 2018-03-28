package com.porterlee.rfiditemlocater;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import com.alien.common.KeyCode;
import com.alien.rfid.RFID;
import com.alien.rfid.RFIDCallback;
import com.alien.rfid.RFIDReader;
import com.alien.rfid.ReaderException;
import com.alien.rfid.Tag;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Pattern;

import me.zhanghai.android.materialprogressbar.MaterialProgressBar;

public class InventoryActivity extends AppCompatActivity {
    private static final String FILE_PATH = "storage/sdcard0/PLCRFID/itemlocater/";
    private RFIDReader reader;
    //private SimpleAdapter targetListAdapter;
    private RecyclerView.Adapter<RFIDItemViewHolder> targetRecyclerAdapter;
    private ArrayList<RFIDTag> targets = new ArrayList<>();
    private int topRSSIIndex = -1;
    private boolean playAudio = false;
    private Timer timer = new Timer();
    private boolean triggered = false;
    private TimerTask playTone = new TimerTask() {
        @Override
        public void run() {
            if(playAudio && topRSSIIndex >= 0 && !targets.get(topRSSIIndex).isMuted) {
                double progress = (targets.get(topRSSIIndex).rssi + 75) / .4;
                progress = (progress > 100) ? 100 : ((progress < 0) ? 0 : progress);
                generateTone((progress * 5) + 500, 50).play();
            }
            playAudio = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!tryInitReader()) {
            finish();
            return;
        }

        setContentView(R.layout.activity_inventory);

        final RecyclerView targetsRecyclerView = findViewById(R.id.target_recycler_view);
        targetsRecyclerView.setHasFixedSize(true);
        targetsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        targetRecyclerAdapter = new RecyclerView.Adapter<RFIDItemViewHolder>() {
            @Override
            public RFIDItemViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                return new RFIDItemViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.rfid_item, parent, false));
            }

            @Override
            public void onBindViewHolder(RFIDItemViewHolder holder, int position) {
                holder.bindViews(targets.get(holder.getAdapterPosition()));
            }

            @Override
            public int getItemCount() {
                return targets.size();
            }
        };
        targetsRecyclerView.setAdapter(targetRecyclerAdapter);
        findViewById(R.id.start_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleGeiger();
            }
        });
        timer.scheduleAtFixedRate(playTone, 0, 100);
        setTargets();
    }

    private boolean tryInitReader() {
        try {
            // initialize RFID interface and obtain a global RFID Reader instance
            reader = RFID.open();
        } catch(ReaderException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(this, "This device is not supported", Toast.LENGTH_SHORT).show();
        } finally {
            if (reader == null) {
                //noinspection ReturnInsideFinallyBlock
                return false;
            }
        }

        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!tryInitReader()) {
            finish();
            //return;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        reader.close();
        reader = null;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyCode.ALR_H450.SCAN && event.getRepeatCount() == 0) {
            startScan();
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
        if (keyCode == KeyCode.ALR_H450.SCAN) {
            stopScan();
            return true;
        }

        return super.onKeyUp(keyCode, event);
    }

    private void startScan() {

        if (reader == null || reader.isRunning()) return;

        try {
            // create a callback to receive tag data
            reader.inventory(new RFIDCallback() {
                @Override
                public void onTagRead(Tag tag) {
                    onRead(tag.getEPC(), tag.getRSSI());
                }
            });

            this.<Button>findViewById(R.id.start_button).setText(R.string.geiger_on);
            triggered = true;
        } catch (ReaderException e) {
            Toast.makeText(this, "Could not start inventory: " + e, Toast.LENGTH_LONG).show();
        }
    }

    private void stopScan() {
        if (reader == null || !reader.isRunning()) return;

        // stop continuous inventory
        try {
            reader.stop();

            Button btn = findViewById(R.id.start_button);
            btn.setText(getString(R.string.geiger_off));
            triggered = false;
        } catch(ReaderException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }

        for(int i = 0; i < targets.size(); i++) {
            targets.get(i).rssi = -75d;
        }

        targetRecyclerAdapter.notifyDataSetChanged();
    }

    private void setTargets() {
        File temp1 = new File(FILE_PATH + "targets.new");
        if(temp1.delete() || !temp1.exists()) {
            targets.clear();
            Scanner fileIn;

            try {
                fileIn = new Scanner(new File(FILE_PATH + "targets.txt"));
                String[] buffer;

                while (fileIn.hasNextLine()) {
                    buffer = fileIn.nextLine().split(Pattern.quote(","));
                    addTarget(buffer[0], buffer[1]);
                }

                if (targets.size() > 0) {
                    Toast.makeText(this, "Targets set", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "No targets set", Toast.LENGTH_SHORT).show();
                }

                try {
                    File temp2 = new File(FILE_PATH + "targets.old");
                    if (!temp2.createNewFile() && !temp2.exists())
                        Toast.makeText(this, "Could not create targets.old", Toast.LENGTH_SHORT).show();
                } catch (IOException e) {
                    Toast.makeText(this, "Could not create targets.old", Toast.LENGTH_SHORT).show();
                    e.printStackTrace();
                }
            } catch (FileNotFoundException e) {
                Toast.makeText(this, "Could not read target file", Toast.LENGTH_SHORT).show();
                e.printStackTrace();
            }
        }
    }

    public void toggleGeiger() {
        if (reader.isRunning()) {
            stopScan();
        } else {
            startScan();
        }
    }

    private AudioTrack generateTone(double freqHz, int durationMs) {
        int count = (int)(44100.0 * 2.0 * (durationMs / 1000.0)) & ~1;
        short[] samples = new short[count];
        for(int i = 0; i < count; i += 2){
            short sample = (short)(Math.sin(2 * Math.PI * i / (44100.0 / freqHz)) * 0x7FFF);
            samples[i] = sample;
            samples[i + 1] = sample;
        }
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, 44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, count * (Short.SIZE / 8), AudioTrack.MODE_STATIC);
        track.write(samples, 0, count);
        return track;
    }

    public void addTarget(String epc, String description) {
        RFIDTag tag = new RFIDTag();
        tag.epc = epc;
        tag.description = description;
        tag.reads = 0;
        tag.rssi = -75d;
        targets.add(tag);
    }

    public void onRead(final String epc, final double rssi) {
        for(int i = 0; i < targets.size(); i++) {
            if (epc.equals(targets.get(i).epc)) {
                targets.get(i).reads += 1;
                targets.get(i).rssi = rssi;
                playAudio = true;
                if (topRSSIIndex >= 0) {
                    if ((i == topRSSIIndex || rssi > targets.get(topRSSIIndex).rssi) && !targets.get(i).isMuted) {
                        topRSSIIndex = i;
                    }
                } else
                    topRSSIIndex = i;

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        targetRecyclerAdapter.notifyDataSetChanged();
                    }
                });
            }
        }
    }

    class RFIDItemViewHolder extends RecyclerView.ViewHolder{
        MaterialProgressBar rssiProgressBar;
        CheckBox mutedCheckbox;
        TextView readsTextView;
        RFIDTag tag;

        RFIDItemViewHolder(View itemView) {
            super(itemView);

            rssiProgressBar = itemView.findViewById(R.id.rssi_progress_bar);
            rssiProgressBar.setProgressDrawable(ContextCompat.getDrawable(InventoryActivity.this, R.drawable.rssi_progress_bar));
            mutedCheckbox = itemView.findViewById(R.id.is_muted_checkbox);
            mutedCheckbox.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { tag.isMuted = mutedCheckbox.isChecked(); }
            });
            readsTextView = itemView.findViewById(R.id.reads_text_view);
        }

        void bindViews(RFIDTag tag) {
            this.tag = tag;
            rssiProgressBar.setProgress(triggered ? (tag.rssi > -35 ? 100 : (tag.rssi < -75 ? 0 : ((int) Math.round((tag.rssi + 75) / .4)))) : 0);
            mutedCheckbox.setChecked(tag.isMuted);
            mutedCheckbox.setText(tag.description);
            readsTextView.setText(String.valueOf(tag.reads));
        }
    }

    public class RFIDTag {
        String epc;
        String description;
        int reads;
        double rssi;
        boolean isMuted;
    }
}
