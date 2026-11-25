package org.tizendlna.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.tizendlna.app.dlna.SsdpDiscovery;
import org.tizendlna.app.dlna.TizenDlnaServer;
import org.tizendlna.app.dlna.TvController;
import org.tizendlna.app.media.MediaScanner;
import org.tizendlna.app.model.MediaItem;
import org.tizendlna.app.model.TvDevice;
import org.tizendlna.app.service.DlnaServerService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pure Android 16 native UI (zero third-party UI frameworks, Vulkan HWUI accelerated).
 */
public final class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 101;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "tizendlna-bg");
        t.setDaemon(true);
        return t;
    });

    private DlnaServerService serverService;
    private boolean serviceBound;

    private final List<TvDevice> discoveredTvs = new ArrayList<>();
    private TvDevice selectedTv;

    private final List<MediaItem> videos = new ArrayList<>();
    private VideoAdapter videoAdapter;

    private MediaItem currentItem;
    private boolean isPlaying;
    private boolean userSeeking;
    private long currentDurationMs;
    private long currentPositionMs;
    private int currentVolume = -1;

    // Views
    private TextView tvServerStatus;
    private TextView tvSelectedTv;
    private LinearLayout permissionBanner;
    private TextView tvEmpty;
    private ListView videoList;
    private LinearLayout nowPlayingBar;
    private TextView tvNowTitle;
    private TextView tvNowSub;
    private TextView tvTime;
    private SeekBar seekBar;
    private Button btnPlayPause;

    private final Runnable statusPoller = new Runnable() {
        @Override
        public void run() {
            final TvDevice tv = selectedTv;
            final MediaItem item = currentItem;
            if (tv == null || item == null) return;

            bgExecutor.execute(() -> {
                try {
                    TvController.PlaybackStatus st = TvController.pollStatus(tv);
                    mainHandler.post(() -> {
                        if (currentItem == null) return;
                        isPlaying = "PLAYING".equals(st.transportState) || "TRANSITIONING".equals(st.transportState);
                        btnPlayPause.setText(isPlaying ? "Pause" : "Play");
                        if (st.durationMs > 0) currentDurationMs = st.durationMs;
                        currentPositionMs = st.positionMs;
                        if (!userSeeking && currentDurationMs > 0) {
                            seekBar.setMax((int) (currentDurationMs / 1000L));
                            seekBar.setProgress((int) (currentPositionMs / 1000L));
                        }
                        tvTime.setText(MediaItem.formatHumanDuration(currentPositionMs)
                                + " / " + MediaItem.formatHumanDuration(currentDurationMs));
                    });
                } catch (Exception ignored) {
                }
            });
            mainHandler.postDelayed(this, 1000);
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            serverService = ((DlnaServerService.LocalBinder) binder).getService();
            serviceBound = true;
            updateServerHeader();
            serverService.getServer().updateCatalog(videos);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            serverService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Android 16 edge-to-edge inset padding
        View root = findViewById(R.id.root_container);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        tvServerStatus = findViewById(R.id.tv_server_status);
        tvSelectedTv = findViewById(R.id.tv_selected_tv);
        permissionBanner = findViewById(R.id.permission_banner);
        tvEmpty = findViewById(R.id.tv_empty);
        videoList = findViewById(R.id.video_list);
        nowPlayingBar = findViewById(R.id.now_playing_bar);
        tvNowTitle = findViewById(R.id.tv_now_title);
        tvNowSub = findViewById(R.id.tv_now_sub);
        tvTime = findViewById(R.id.tv_time);
        seekBar = findViewById(R.id.seek_bar);
        btnPlayPause = findViewById(R.id.btn_play_pause);

        videoAdapter = new VideoAdapter();
        videoList.setAdapter(videoAdapter);
        videoList.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < videos.size()) {
                castVideo(videos.get(position));
            }
        });

        findViewById(R.id.btn_scan_tv).setOnClickListener(v -> scanTvs());
        findViewById(R.id.btn_refresh).setOnClickListener(v -> scanVideos());
        tvSelectedTv.setOnClickListener(v -> showTvPicker());
        findViewById(R.id.btn_grant_files).setOnClickListener(v -> openAllFilesPermission());

        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        findViewById(R.id.btn_stop).setOnClickListener(v -> stopCasting());
        findViewById(R.id.btn_rew).setOnClickListener(v -> seekRelative(-15_000));
        findViewById(R.id.btn_fwd).setOnClickListener(v -> seekRelative(15_000));
        findViewById(R.id.btn_vol_down).setOnClickListener(v -> adjustVolume(-3));
        findViewById(R.id.btn_vol_up).setOnClickListener(v -> adjustVolume(3));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    tvTime.setText(MediaItem.formatHumanDuration(progress * 1000L)
                            + " / " + MediaItem.formatHumanDuration(currentDurationMs));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                seekTo(bar.getProgress() * 1000L);
            }
        });

        Intent svc = new Intent(this, DlnaServerService.class);
        startForegroundService(svc);
        bindService(svc, serviceConnection, Context.BIND_AUTO_CREATE);

        requestBasicPermissions();
        scanTvs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionBanner();
        scanVideos();
        if (serverService != null) {
            serverService.refreshHostIp();
            updateServerHeader();
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(statusPoller);
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        bgExecutor.shutdownNow();
        super.onDestroy();
    }

    private void requestBasicPermissions() {
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_MEDIA_VIDEO);
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            scanVideos();
        }
    }

    private void updatePermissionBanner() {
        boolean hasAllFiles = Environment.isExternalStorageManager();
        permissionBanner.setVisibility(hasAllFiles ? View.GONE : View.VISIBLE);
    }

    private void openAllFilesPermission() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private void updateServerHeader() {
        if (serverService == null) return;
        TizenDlnaServer s = serverService.getServer();
        tvServerStatus.setText("DMS " + s.getHostIp() + ":" + s.getPort());
    }

    private void scanVideos() {
        bgExecutor.execute(() -> {
            List<MediaItem> found = MediaScanner.scanVideos(getApplicationContext());
            mainHandler.post(() -> {
                videos.clear();
                videos.addAll(found);
                videoAdapter.notifyDataSetChanged();
                tvEmpty.setVisibility(videos.isEmpty() ? View.VISIBLE : View.GONE);
                if (serverService != null) {
                    serverService.getServer().updateCatalog(videos);
                }
            });
        });
    }

    private void scanTvs() {
        tvSelectedTv.setText("Scanning Wi-Fi for Samsung TVs...");
        bgExecutor.execute(() -> {
            List<TvDevice> tvs = SsdpDiscovery.discover(getApplicationContext(), 2200);
            mainHandler.post(() -> {
                discoveredTvs.clear();
                discoveredTvs.addAll(tvs);
                if (discoveredTvs.isEmpty()) {
                    selectedTv = null;
                    tvSelectedTv.setText("No TV found — tap Scan TVs");
                } else {
                    if (selectedTv == null || !containsTv(discoveredTvs, selectedTv.udn)) {
                        selectedTv = discoveredTvs.get(0);
                    }
                    tvSelectedTv.setText("TV: " + selectedTv.displayLabel()
                            + (discoveredTvs.size() > 1 ? "  ▾" : ""));
                }
            });
        });
    }

    private static boolean containsTv(List<TvDevice> list, String udn) {
        for (TvDevice d : list) {
            if (d.udn.equals(udn)) return true;
        }
        return false;
    }

    private void showTvPicker() {
        if (discoveredTvs.isEmpty()) {
            scanTvs();
            return;
        }
        String[] names = new String[discoveredTvs.size()];
        for (int i = 0; i < discoveredTvs.size(); i++) {
            TvDevice d = discoveredTvs.get(i);
            names[i] = (d.isSamsung ? "[Samsung] " : "") + d.displayLabel() + " (" + d.hostIp + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("Select TV")
                .setItems(names, (dialog, which) -> {
                    selectedTv = discoveredTvs.get(which);
                    currentVolume = -1;
                    tvSelectedTv.setText("TV: " + selectedTv.displayLabel()
                            + (discoveredTvs.size() > 1 ? "  ▾" : ""));
                })
                .setNeutralButton("Rescan", (d, w) -> scanTvs())
                .show();
    }

    private void castVideo(MediaItem item) {
        if (selectedTv == null) {
            Toast.makeText(this, "No TV selected — tap Scan TVs first", Toast.LENGTH_SHORT).show();
            scanTvs();
            return;
        }
        if (serverService == null) {
            Toast.makeText(this, "DLNA server starting...", Toast.LENGTH_SHORT).show();
            return;
        }
        serverService.refreshHostIp();
        updateServerHeader();

        final TvDevice tv = selectedTv;
        final TizenDlnaServer server = serverService.getServer();

        nowPlayingBar.setVisibility(View.VISIBLE);
        tvNowTitle.setText(item.title);
        tvNowSub.setText(item.hasSubtitle()
                ? "SUB: " + item.subtitleFile.getName()
                : "No subtitle in folder");
        btnPlayPause.setText("...");

        bgExecutor.execute(() -> {
            try {
                TvController.pushAndPlay(tv, item, server);
                int vol = TvController.getVolume(tv);
                mainHandler.post(() -> {
                    currentItem = item;
                    currentDurationMs = item.durationMs;
                    currentPositionMs = 0;
                    isPlaying = true;
                    currentVolume = vol;
                    btnPlayPause.setText("Pause");
                    if (serverService != null) {
                        serverService.updateNotification("Casting: " + item.title
                                + (item.hasSubtitle() ? " [SUB]" : ""));
                    }
                    mainHandler.removeCallbacks(statusPoller);
                    mainHandler.postDelayed(statusPoller, 1000);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    btnPlayPause.setText("Play");
                    Toast.makeText(this, "Cast failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void togglePlayPause() {
        final TvDevice tv = selectedTv;
        if (tv == null) return;
        final boolean pauseNow = isPlaying;
        bgExecutor.execute(() -> {
            try {
                if (pauseNow) TvController.pause(tv);
                else TvController.play(tv);
                mainHandler.post(() -> {
                    isPlaying = !pauseNow;
                    btnPlayPause.setText(isPlaying ? "Pause" : "Play");
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void stopCasting() {
        final TvDevice tv = selectedTv;
        mainHandler.removeCallbacks(statusPoller);
        currentItem = null;
        isPlaying = false;
        nowPlayingBar.setVisibility(View.GONE);
        if (serverService != null) {
            TizenDlnaServer s = serverService.getServer();
            serverService.updateNotification("DLNA server ready on " + s.getHostIp() + ":" + s.getPort());
        }
        if (tv != null) {
            bgExecutor.execute(() -> {
                try {
                    TvController.stop(tv);
                } catch (Exception ignored) {
                }
            });
        }
    }

    private void seekRelative(long deltaMs) {
        seekTo(Math.max(0, currentPositionMs + deltaMs));
    }

    private void seekTo(long targetMs) {
        final TvDevice tv = selectedTv;
        if (tv == null) return;
        currentPositionMs = targetMs;
        bgExecutor.execute(() -> {
            try {
                TvController.seek(tv, targetMs);
            } catch (Exception ignored) {
            }
        });
    }

    private void adjustVolume(int delta) {
        final TvDevice tv = selectedTv;
        if (tv == null) return;
        bgExecutor.execute(() -> {
            try {
                int base = currentVolume >= 0 ? currentVolume : TvController.getVolume(tv);
                if (base < 0) base = 15;
                int next = Math.max(0, Math.min(100, base + delta));
                TvController.setVolume(tv, next);
                currentVolume = next;
                mainHandler.post(() ->
                        Toast.makeText(this, "TV Volume: " + next, Toast.LENGTH_SHORT).show());
            } catch (Exception ignored) {
            }
        });
    }

    private final class VideoAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(MainActivity.this);

        @Override
        public int getCount() {
            return videos.size();
        }

        @Override
        public MediaItem getItem(int position) {
            return videos.get(position);
        }

        @Override
        public long getItemId(int position) {
            return videos.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder vh;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_video, parent, false);
                vh = new ViewHolder(convertView);
                convertView.setTag(vh);
            } else {
                vh = (ViewHolder) convertView.getTag();
            }

            MediaItem item = videos.get(position);
            vh.title.setText(item.title);

            String dur = MediaItem.formatHumanDuration(item.durationMs);
            String meta = item.folderName + "  •  " + MediaItem.formatHumanSize(item.sizeBytes)
                    + (dur.isEmpty() ? "" : "  •  " + dur);
            vh.meta.setText(meta);

            if (item.hasSubtitle()) {
                vh.subBadge.setVisibility(View.VISIBLE);
                vh.subBadge.setText(item.subtitleType.toUpperCase(Locale.ROOT));
            } else {
                vh.subBadge.setVisibility(View.GONE);
            }
            return convertView;
        }
    }

    private static final class ViewHolder {
        final TextView title;
        final TextView meta;
        final TextView subBadge;

        ViewHolder(View root) {
            title = root.findViewById(R.id.item_title);
            meta = root.findViewById(R.id.item_meta);
            subBadge = root.findViewById(R.id.item_sub_badge);
        }
    }
}
