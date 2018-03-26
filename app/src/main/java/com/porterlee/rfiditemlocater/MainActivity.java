package com.porterlee.rfiditemlocater;


import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SimpleAdapter;
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
import java.util.HashMap;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Pattern;

public class InventoryActivity extends AppCompatActivity {
    private static final String FILE_PATH = "storage/sdcard0/PLCRFID/itemlocater/";
    private static final String TARGET_EPC = "epc";
    private static final String TARGET_DESCRIPTION = "description";
    private static final String TARGET_READS = "reads";
    private static final String TARGET_RSSI = "rssi";
    private static final String TARGET_MUTE = "mute";
    private static final String TARGET_INDEX = "index";
    private ListView targetsListView;
    private RFIDReader reader;
    private SimpleAdapter targetListAdapter;
    private ArrayList<HashMap<String, String>> targets = new ArrayList<>();
    private Pair<Integer, Double> topRssi = new Pair<>(0, (double) -75);
    private boolean playAudio = false;
    private Timer timer = new Timer();
    boolean triggered;
    private TimerTask playTone = new TimerTask() {
        @Override
        public void run() {
            if(playAudio && !Boolean.parseBoolean(targets.get(topRssi.first).get(TARGET_MUTE))) {
                int progress = (int) ((topRssi.second + 75) * 3);
                progress = (progress > 100) ? 100 : ((progress < 0) ? 0 : progress);
                generateTone((progress * 5) + 500, 50).play();
            }
            playAudio = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inventory);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        targetsListView = (ListView) findViewById(R.id.targetListView);
        targetListAdapter = new SimpleAdapter(this, targets, R.layout.taglist_item, new String[] { TARGET_INDEX, TARGET_DESCRIPTION, TARGET_READS, TARGET_RSSI }, new int[] { R.id.muteBox, R.id.desc, R.id.reads, R.id.rssiBar});
        targetListAdapter.setViewBinder(new SimpleAdapter.ViewBinder() {
            @Override
            public boolean setViewValue(View view, Object data, String textRepresentation) {
                if (view.getId() == R.id.rssiBar) {
                    double rssi = Double.parseDouble((String) data);
                    if (rssi > -35) {
                        ((ProgressBar) view).setProgress(100);
                    } else if (rssi < -75) {
                        ((ProgressBar) view).setProgress(0);
                    } else {
                        int progress = (int) ((rssi + 75) * 3);
                        progress = (progress > 100) ? 100 : ((progress < 0) ? 0 : progress);
                        ((ProgressBar) view).setProgress(progress);
                    }
                    if(!triggered) {
                        ((ProgressBar) view).setProgress(0);
                    }
                    return true;
                } else if (view.getId() == R.id.muteBox) {
                    view.setContentDescription((String) data);
                    return true;
                }
                return false;
            }
        });
        targetsListView.setAdapter(targetListAdapter);
        timer.scheduleAtFixedRate(playTone, 0, 100);
        onSetTargets(null);

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);


        try {
            // initialize RFID interface and obtain a global RFID Reader instance
            reader = RFID.open();
        } catch(ReaderException e) {
            Toast.makeText(this, "RFID init failed: " + e, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        setTargets();
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        // close RFID interface
        if (reader != null) reader.close();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_inventory, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        return super.onOptionsItemSelected(item);
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
        triggered = true;
        try {
            new File(FILE_PATH + "targets.old").createNewFile();
        } catch (IOException e) {
            Toast.makeText(this, "Cannot create targets.old", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
        if (reader == null || reader.isRunning()) return;

        try {
            // start continuous inventory
            Button btn = (Button) findViewById(R.id.btnStartGeiger);
            btn.setText("Geiger on");

            // create a callback to receive tag data
            reader.inventory(new RFIDCallback() {
                @Override
                public void onTagRead(Tag tag) {
                    onRead(tag.getEPC(), tag.getRSSI());
                }
            });
        } catch (ReaderException e) {
            Toast.makeText(this, "ERROR: " + e, Toast.LENGTH_LONG).show();
        }
    }

    private void stopScan() {
        triggered = false;
        if (reader == null || !reader.isRunning()) return;

        // stop continuous inventory
        try {
            reader.stop();

            Button btn = (Button) findViewById(R.id.btnStartGeiger);
            btn.setText("Geiger off");

        } catch(ReaderException e) {
            Toast.makeText(this, "ERROR: " + e, Toast.LENGTH_LONG).show();
        }
        for(int i = 0; i < targets.size(); i++) {
            targets.get(i).put(TARGET_RSSI, "-75");
        }
        targetListAdapter.notifyDataSetChanged();
    }

    public void onMute(View view) {
        targets.get(Integer.parseInt(String.valueOf(view.getContentDescription()))).put(TARGET_MUTE, String.valueOf(((CheckBox) view).isChecked()));
    }

    private void setTargets() {
        if(new File(FILE_PATH + "targets.new").delete()) {
            onSetTargets(null);
        }
    }

    public void onSetTargets(View view) {
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
        } catch (FileNotFoundException e) {
            Toast.makeText(this, "Could not read target file", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
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
        HashMap<String, String> hash = new HashMap<>();
        hash.put(TARGET_EPC, epc);
        hash.put(TARGET_DESCRIPTION, description);
        hash.put(TARGET_READS, "0");
        hash.put(TARGET_RSSI, "-75");
        hash.put(TARGET_INDEX, String.valueOf(targets.size()));
        targets.add(hash);
    }

    public void onStartGeiger(View view) {
        if (reader.isRunning()) {
            stopScan();
        } else {
            startScan();
        }
    }

    public void onRead(final String epc, final double rssi) {
        for(int i = 0; i < targets.size(); i++) {
            if (epc.equals(targets.get(i).get(TARGET_EPC))) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        for (int i = 0; i < targets.size(); i++) {
                            if (targets.get(i).get(TARGET_EPC).equals(epc)) {
                                int c = Integer.parseInt(targets.get(i).get(TARGET_READS)) + 1;
                                targets.get(i).put(TARGET_READS, String.valueOf(c));
                                targets.get(i).put(TARGET_RSSI, String.valueOf(rssi));
                                targetListAdapter.notifyDataSetChanged();
                                playAudio = true;
                                if((i == topRssi.first || rssi > topRssi.second) && !Boolean.parseBoolean(targets.get(i).get(TARGET_MUTE))) {
                                    topRssi = new Pair<>(i, rssi);
                                }
                                return;
                            }
                        }
                    }
                });
            }
        }
    }
}