package com.porterlee.rfiditemlocater;

import android.content.DialogInterface;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.v4.os.EnvironmentCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Pattern;

import me.zhanghai.android.materialprogressbar.MaterialProgressBar;

public class InventoryActivity extends AppCompatActivity {
    private static final String TAG = InventoryActivity.class.getName();
    private static final String TARGET_LIST = "target_list";
    private static final File FILE_PATH = new File(Environment.getExternalStorageDirectory(), "PLCRFID/itemlocater/");
    private static final File INPUT_FILE = new File(FILE_PATH, "targets.txt");
    private static final File INPUT_FILE_NEW = new File(FILE_PATH, "targets.new");
    private static final File INPUT_FILE_OLD = new File(FILE_PATH, "targets.old");
    private static final int BEEP_LENGH = 50;
    //private FileObserver mFileObserver;
    private AlertDialog refreshDialog;
    private RFIDReader reader;
    private Menu mOptionsMenu;
    //private SimpleAdapter targetListAdapter;
    private RecyclerView.Adapter<RFIDItemViewHolder> targetRecyclerAdapter;
    private ArrayList<RFIDTag> targets;
    private int topRSSIIndex = -1;
    private boolean playAudio = false;
    private Timer timer = new Timer();
    private boolean triggered = false;
    private TimerTask playTone = new TimerTask() {
        @Override
        public void run() {
            if (playAudio && topRSSIIndex >= 0 && !targets.get(topRSSIIndex).isMuted) {
                double progress = (targets.get(topRSSIIndex).rssi + 75) / .4;
                progress = (progress > 100) ? 100 : ((progress < 0) ? 0 : progress);
                generateTone((progress * 5) + 500).play();
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

        if (getSupportActionBar() != null)
            getSupportActionBar().setTitle(String.format("%1s v%2s", getString(R.string.app_name), BuildConfig.VERSION_NAME));

        targets = savedInstanceState != null ? savedInstanceState.<RFIDTag>getParcelableArrayList(TARGET_LIST) : null;
        if (targets == null)
            targets = new ArrayList<>();
        init();
    }

    private void init() {
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

        /*mFileObserver = new FileObserver(FILE_PATH.getAbsolutePath()) {
            @Override
            public void onEvent(int event, @Nullable String path) {
                Log.d(TAG, "onEvent() | int event = "  + Integer.toBinaryString(event));
                if ((event & (FileObserver.CREATE | FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE | FileObserver.MODIFY)) != 0 && INPUT_FILE.exists()) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            askForRefresh();
                        }
                    });
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshFileMenuOption();
                    }
                });
            }
        };*/
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelableArrayList(TARGET_LIST, targets);
    }

    private boolean tryInitReader() {
        try {
            reader = RFID.open();
        } catch(ReaderException e) {
            e.printStackTrace();
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            e.printStackTrace();
            Toast.makeText(this, "This device is not supported", Toast.LENGTH_SHORT).show();
        }

        return reader != null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mOptionsMenu = menu;
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.inventory_menu, menu);
        //refreshFileMenuOption();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_refresh_list:
                if (!isShared(INPUT_FILE))
                    askForRefresh();
                else
                    Toast.makeText(this, "USB mass storage must be turned off first", Toast.LENGTH_SHORT).show();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        //mFileObserver.startWatching();
        if (!tryInitReader()) {
            finish();
            //return;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        //mFileObserver.stopWatching();
        if (reader != null) {
            reader.close();
            reader = null;
        }
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

    private void askForRefresh() {
        if (refreshDialog == null) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Refresh List");
            builder.setMessage("Would you like to refresh the list?");
            builder.setNegativeButton("no", null);
            builder.setPositiveButton("yes", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    setTargets();
                }
            });
            refreshDialog = builder.create();
        }

        refreshDialog.show();
    }

    /*private void refreshFileMenuOption() {
        if (mOptionsMenu != null)
            mOptionsMenu.findItem(R.id.action_refresh_list).setEnabled(INPUT_FILE.exists());
    }*/

    private void refreshExternalPath() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            Uri contentUri = Uri.fromFile(FILE_PATH);
            mediaScanIntent.setData(contentUri);
            sendBroadcast(mediaScanIntent);
        } else {
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_MOUNTED, Uri.fromFile(FILE_PATH)));
        }
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
        refreshExternalPath();
        if(INPUT_FILE_NEW.delete() || !INPUT_FILE_NEW.exists()) {
            targetRecyclerAdapter.notifyItemRangeRemoved(0, targets.size());
            targets.clear();
            LineNumberReader fileIn = null;

            try {
                fileIn = new LineNumberReader(new FileReader(INPUT_FILE));
                String[] buffer;

                String line;
                while ((line = fileIn.readLine()) != null) {
                    buffer = line.split(Pattern.quote(","));
                    try {
                        String epc = buffer[0];
                        String description = buffer[1];
                        targetRecyclerAdapter.notifyItemInserted(targets.size());
                        targets.add(new RFIDTag(epc, description, -75));
                    } catch (ArrayIndexOutOfBoundsException e) {
                        throw new ParseException("Not enough tokens", fileIn.getLineNumber());
                    }
                }

                if (targets.size() > 0) {
                    Toast.makeText(this, "Targets set", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "No targets set", Toast.LENGTH_LONG).show();
                }

                try {
                    if (!INPUT_FILE_OLD.createNewFile() && !INPUT_FILE_OLD.exists())
                        Toast.makeText(this, "Could not create targets.old", Toast.LENGTH_LONG).show();
                } catch (IOException e) {
                    Toast.makeText(this, "Could not create targets.old", Toast.LENGTH_LONG).show();
                    e.printStackTrace();
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                if (isShared(INPUT_FILE))
                    Toast.makeText(this, "Turn off USB mass storage first", Toast.LENGTH_SHORT).show();
                else
                    Toast.makeText(this, "Could not read target file", Toast.LENGTH_LONG).show();
            } catch (ParseException e) {
                e.printStackTrace();
                Toast.makeText(this, "Target file formatted incorrectly: " + e.getMessage(), Toast.LENGTH_LONG).show();
            } catch (IOException e) {
                e.printStackTrace();
                Toast.makeText(this, "An IOException occured: " + e.getMessage(), Toast.LENGTH_LONG).show();
            } finally {
                if (fileIn != null) {
                    try {
                        fileIn.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        } else {
            Toast.makeText(this, "Could not delete targets.new", Toast.LENGTH_LONG).show();
        }
    }

    public boolean isShared(File file) {
        return EnvironmentCompat.getStorageState(file).equals(Environment.MEDIA_SHARED);
    }

    public void toggleGeiger() {
        if (reader != null && reader.isRunning()) {
            stopScan();
        } else {
            startScan();
        }
    }

    private AudioTrack generateTone(double freqHz) {
        int count = (int)(44100.0 * 2.0 * (BEEP_LENGH / 1000.0)) & ~1;
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

    public void onRead(final String epc, final double rssi) {
        for(int i = 0; i < targets.size(); i++) {
            if (epc.equals(targets.get(i).epc)) {
                targets.get(i).reads += 1;
                targets.get(i).rssi = rssi;
                if (!targets.get(i).isMuted) {
                    playAudio = true;
                }
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

    public static class RFIDTag implements Parcelable{
        String epc;
        String description;
        int reads;
        double rssi;
        boolean isMuted;

        RFIDTag(String epc, String description, double rssi) {
            this.epc = epc;
            this.description = description;
            this.reads = 0;
            this.rssi = rssi;
            this.isMuted = false;
        }

        RFIDTag(Parcel in) {
            epc = in.readString();
            description = in.readString();
            reads = in.readInt();
            rssi = in.readDouble();
            isMuted = in.readByte() != 0;
        }

        public static final Creator<RFIDTag> CREATOR = new Creator<RFIDTag>() {
            @Override
            public RFIDTag createFromParcel(Parcel in) {
                return new RFIDTag(in);
            }

            @Override
            public RFIDTag[] newArray(int size) {
                return new RFIDTag[size];
            }
        };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(epc);
            dest.writeString(description);
            dest.writeInt(reads);
            dest.writeDouble(rssi);
            dest.writeByte((byte) (isMuted ? 1 : 0));
        }
    }
}
