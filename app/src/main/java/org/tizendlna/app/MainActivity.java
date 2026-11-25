package org.tizendlna.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.tizendlna.app.dlna.SsdpDiscovery;
import org.tizendlna.app.dlna.TizenDlnaServer;
import org.tizendlna.app.dlna.TvController;
import org.tizendlna.app.model.MediaItem;
import org.tizendlna.app.model.TvDevice;
import org.tizendlna.app.service.DlnaServerService;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pure Android 16 Material You native File Manager & Samsung Tizen DLNA Push Controller.
 * Zero third-party libraries, zero background media scanning, Vulkan HWUI accelerated.
 */
public final class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 101;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgExecutor = Executors.newFixedThreadPool(3, r -> {
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

    private File storageRoot;
    private File currentDir;
    private final List<FileEntry> entries = new ArrayList<>();
    private FileAdapter fileAdapter;

    private MediaItem currentItem;
    private MediaItem pendingSharedItem;
    private boolean isPlaying;
    private boolean userSeeking;
    private long currentDurationMs;
    private long currentPositionMs;
    private int currentVolume = -1;

    // Views
    private TextView tvServerStatus;
    private Button btnToggleServer;
    private TextView tvSelectedTv;
    private LinearLayout permissionBanner;
    private ImageButton btnUpDir;
    private TextView tvCurrentPath;
    private TextView tvEmpty;
    private ListView fileList;
    private LinearLayout nowPlayingBar;
    private TextView tvNowTitle;
    private TextView tvNowSub;
    private TextView tvTime;
    private SeekBar seekBar;
    private Button btnPlayPause;

    private OnBackInvokedCallback backCallback;

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
                        boolean wasPlaying = isPlaying;
                        isPlaying = "PLAYING".equals(st.transportState) || "TRANSITIONING".equals(st.transportState);
                        btnPlayPause.setText(isPlaying ? "Pause" : "Play");
                        if (wasPlaying != isPlaying && serverService != null) {
                            serverService.updateCastingState(currentItem.title, isPlaying, currentItem.hasSubtitle());
                        }
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
            serverService.setStateListener(new DlnaServerService.StateListener() {
                @Override
                public void onServerStateChanged(boolean running) {
                    if (!running && currentItem != null) {
                        mainHandler.removeCallbacks(statusPoller);
                        currentItem = null;
                        isPlaying = false;
                        nowPlayingBar.setVisibility(View.GONE);
                    }
                    updateServerUi();
                }

                @Override
                public void onTogglePlayPauseRequested() {
                    togglePlayPause();
                }
            });
            updateServerUi();
            syncCurrentFolderToDms();
            tryCastPendingSharedItem();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            serverService = null;
            updateServerUi();
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

        storageRoot = Environment.getExternalStorageDirectory();
        currentDir = storageRoot;

        tvServerStatus = findViewById(R.id.tv_server_status);
        btnToggleServer = findViewById(R.id.btn_toggle_server);
        tvSelectedTv = findViewById(R.id.tv_selected_tv);
        permissionBanner = findViewById(R.id.permission_banner);
        btnUpDir = findViewById(R.id.btn_up_dir);
        tvCurrentPath = findViewById(R.id.tv_current_path);
        tvEmpty = findViewById(R.id.tv_empty);
        fileList = findViewById(R.id.file_list);
        nowPlayingBar = findViewById(R.id.now_playing_bar);
        tvNowTitle = findViewById(R.id.tv_now_title);
        tvNowSub = findViewById(R.id.tv_now_sub);
        tvTime = findViewById(R.id.tv_time);
        seekBar = findViewById(R.id.seek_bar);
        btnPlayPause = findViewById(R.id.btn_play_pause);

        fileAdapter = new FileAdapter();
        fileList.setAdapter(fileAdapter);
        fileList.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= entries.size()) return;
            FileEntry entry = entries.get(position);
            if (entry.isDirectory) {
                openDirectory(entry.file);
            } else if (entry.mediaItem != null) {
                castMedia(entry.mediaItem);
            }
        });

        btnToggleServer.setOnClickListener(v -> toggleServer());
        findViewById(R.id.btn_quit).setOnClickListener(v -> quitApp());
        findViewById(R.id.btn_scan_tv).setOnClickListener(v -> scanTvs());
        tvSelectedTv.setOnClickListener(v -> showTvPicker());
        findViewById(R.id.btn_grant_files).setOnClickListener(v -> openAllFilesPermission());
        btnUpDir.setOnClickListener(v -> navigateUp());
        findViewById(R.id.btn_home_dir).setOnClickListener(v -> openDirectory(storageRoot));

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

        // Android 16 predictive back navigation: navigate up a folder before exiting
        backCallback = () -> {
            if (!navigateUp()) {
                finish();
            }
        };
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);

        Intent svc = new Intent(this, DlnaServerService.class);
        startForegroundService(svc);
        bindService(svc, serviceConnection, Context.BIND_AUTO_CREATE);

        requestNotificationPermission();
        handleIncomingIntent(getIntent());
        scanTvs();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionBanner();
        openDirectory(currentDir != null ? currentDir : storageRoot);
        if (serverService != null && serverService.isServerRunning()) {
            serverService.refreshHostIp();
        }
        updateServerUi();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(statusPoller);
        if (backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        if (serviceBound) {
            if (serverService != null) serverService.setStateListener(null);
            unbindService(serviceConnection);
            serviceBound = false;
        }
        bgExecutor.shutdownNow();
        super.onDestroy();
    }

    private void requestNotificationPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMISSIONS);
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

    private void updateServerUi() {
        if (serverService != null && serverService.isServerRunning()) {
            TizenDlnaServer s = serverService.getServer();
            tvServerStatus.setText("Running  •  " + s.getHostIp() + ":" + s.getPort());
            btnToggleServer.setText("Stop Server");
        } else {
            tvServerStatus.setText("Server stopped");
            btnToggleServer.setText("Start Server");
        }
    }

    private void toggleServer() {
        if (serverService == null) {
            Intent svc = new Intent(this, DlnaServerService.class);
            startForegroundService(svc);
            bindService(svc, serviceConnection, Context.BIND_AUTO_CREATE);
            return;
        }
        if (serverService.isServerRunning()) {
            stopCasting();
            serverService.stopServer();
            Toast.makeText(this, "DLNA server stopped", Toast.LENGTH_SHORT).show();
        } else {
            Intent svc = new Intent(this, DlnaServerService.class);
            startForegroundService(svc);
            serverService.startServer();
            syncCurrentFolderToDms();
            Toast.makeText(this, "DLNA server started", Toast.LENGTH_SHORT).show();
        }
        updateServerUi();
    }

    private void quitApp() {
        mainHandler.removeCallbacks(statusPoller);
        final TvDevice tv = selectedTv;
        if (tv != null && currentItem != null) {
            bgExecutor.execute(() -> {
                try {
                    TvController.stop(tv);
                } catch (Exception ignored) {
                }
            });
        }
        if (serverService != null) {
            serverService.stopServerAndService();
        } else {
            stopService(new Intent(this, DlnaServerService.class));
        }
        if (serviceBound) {
            if (serverService != null) serverService.setStateListener(null);
            unbindService(serviceConnection);
            serviceBound = false;
        }
        finishAndRemoveTask();
    }

    private boolean navigateUp() {
        if (currentDir == null || storageRoot == null) return false;
        if (currentDir.equals(storageRoot)) return false;
        File parent = currentDir.getParentFile();
        if (parent == null || !parent.getAbsolutePath().startsWith(storageRoot.getAbsolutePath())) {
            openDirectory(storageRoot);
        } else {
            openDirectory(parent);
        }
        return true;
    }

    private void openDirectory(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        currentDir = dir;

        String rootPath = storageRoot.getAbsolutePath();
        String curPath = dir.getAbsolutePath();
        if (curPath.equals(rootPath)) {
            tvCurrentPath.setText("Internal Storage");
            btnUpDir.setEnabled(false);
            btnUpDir.setAlpha(0.35f);
        } else {
            String rel = curPath.startsWith(rootPath) ? curPath.substring(rootPath.length()) : curPath;
            tvCurrentPath.setText("Internal Storage" + rel.replace("/", " / "));
            btnUpDir.setEnabled(true);
            btnUpDir.setAlpha(1.0f);
        }

        File[] raw = dir.listFiles();
        List<FileEntry> dirs = new ArrayList<>();
        List<FileEntry> playable = new ArrayList<>();
        List<FileEntry> others = new ArrayList<>();

        if (raw != null) {
            for (File f : raw) {
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory()) {
                    dirs.add(new FileEntry(f, true, null));
                } else if (f.isFile()) {
                    if (MediaItem.isPlayableMedia(f)) {
                        playable.add(new FileEntry(f, false, MediaItem.fromFile(f)));
                    } else {
                        others.add(new FileEntry(f, false, null));
                    }
                }
            }
        }

        Comparator<FileEntry> byName = Comparator.comparing(e -> e.file.getName().toLowerCase(Locale.ROOT));
        dirs.sort(byName);
        playable.sort(byName);
        others.sort(byName);

        entries.clear();
        entries.addAll(dirs);
        entries.addAll(playable);
        entries.addAll(others);
        fileAdapter.notifyDataSetChanged();
        fileList.setSelection(0);

        if (entries.isEmpty()) {
            tvEmpty.setText(Environment.isExternalStorageManager()
                    ? "This folder is empty."
                    : "Grant All Files Access above to browse storage folders.");
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            tvEmpty.setVisibility(View.GONE);
        }

        syncCurrentFolderToDms();
    }

    private void syncCurrentFolderToDms() {
        if (serverService == null || !serverService.isServerRunning()) return;
        List<MediaItem> folderMedia = new ArrayList<>();
        for (FileEntry e : entries) {
            if (e.mediaItem != null) folderMedia.add(e.mediaItem);
        }
        serverService.getServer().updateCatalog(folderMedia);
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
                    tvSelectedTv.setText("No TV found — tap Scan TV");
                } else {
                    if (selectedTv == null || !containsTv(discoveredTvs, selectedTv.udn)) {
                        selectedTv = discoveredTvs.get(0);
                    }
                    tvSelectedTv.setText(selectedTv.displayLabel()
                            + (discoveredTvs.size() > 1 ? "  ▾" : ""));
                    tryCastPendingSharedItem();
                }
            });
        });
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        }
        if (uri == null) return;

        File resolved = resolveUriToFile(uri);
        if (resolved == null || !resolved.isFile()) {
            Toast.makeText(this, "Could not resolve file path (grant All Files Access)", Toast.LENGTH_LONG).show();
            return;
        }
        if (!MediaItem.isPlayableMedia(resolved)) {
            Toast.makeText(this, "Only audio and video files can be cast", Toast.LENGTH_SHORT).show();
            return;
        }
        File parent = resolved.getParentFile();
        if (parent != null && parent.isDirectory()) {
            currentDir = parent;
        }
        pendingSharedItem = MediaItem.fromFile(resolved);
        tryCastPendingSharedItem();
    }

    private void tryCastPendingSharedItem() {
        if (pendingSharedItem == null) return;
        if (selectedTv == null || serverService == null || !serverService.isServerRunning()) return;
        MediaItem item = pendingSharedItem;
        pendingSharedItem = null;
        castMedia(item);
    }

    private File resolveUriToFile(Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            return path != null ? new File(path) : null;
        }
        // Try MediaStore DATA column
        try (Cursor c = getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(MediaStore.MediaColumns.DATA);
                if (idx >= 0) {
                    String path = c.getString(idx);
                    if (path != null && !path.isEmpty()) {
                        File f = new File(path);
                        if (f.isFile()) return f;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // Try primary external storage document ID (e.g. primary:Movies/clip.mkv)
        try {
            if (DocumentsContract.isDocumentUri(this, uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                if (docId != null && docId.startsWith("primary:")) {
                    File f = new File(Environment.getExternalStorageDirectory(), docId.substring("primary:".length()));
                    if (f.isFile()) return f;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
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
                    tvSelectedTv.setText(selectedTv.displayLabel()
                            + (discoveredTvs.size() > 1 ? "  ▾" : ""));
                })
                .setNeutralButton("Rescan", (d, w) -> scanTvs())
                .show();
    }

    private void castMedia(MediaItem item) {
        if (selectedTv == null) {
            Toast.makeText(this, "No TV selected — tap Scan TV first", Toast.LENGTH_SHORT).show();
            scanTvs();
            return;
        }
        if (serverService == null || !serverService.isServerRunning()) {
            Toast.makeText(this, "Start the DLNA server first", Toast.LENGTH_SHORT).show();
            return;
        }
        serverService.refreshHostIp();
        updateServerUi();

        final TvDevice tv = selectedTv;
        final TizenDlnaServer server = serverService.getServer();

        nowPlayingBar.setVisibility(View.VISIBLE);
        tvNowTitle.setText(item.title);
        if (item.isAudio) {
            tvNowSub.setText("Audio  •  " + MediaItem.formatHumanSize(item.sizeBytes));
        } else if (item.hasSubtitle()) {
            tvNowSub.setText("SUB: " + item.subtitleFile.getName());
        } else {
            tvNowSub.setText("No matching subtitle in folder");
        }
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
                        serverService.updateCastingState(item.title, true, item.hasSubtitle());
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
                    if (serverService != null && currentItem != null) {
                        serverService.updateCastingState(currentItem.title, isPlaying, currentItem.hasSubtitle());
                    }
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
        if (serverService != null && serverService.isServerRunning()) {
            serverService.updateCastingState(null, false, false);
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

    private static final class FileEntry {
        final File file;
        final boolean isDirectory;
        final MediaItem mediaItem; // non-null only for playable audio/video files

        FileEntry(File file, boolean isDirectory, MediaItem mediaItem) {
            this.file = file;
            this.isDirectory = isDirectory;
            this.mediaItem = mediaItem;
        }

        boolean isSelectable() {
            return isDirectory || mediaItem != null;
        }
    }

    private final class FileAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(MainActivity.this);

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public FileEntry getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return position >= 0 && position < entries.size() && entries.get(position).isSelectable();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder vh;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_file, parent, false);
                vh = new ViewHolder(convertView);
                convertView.setTag(vh);
            } else {
                vh = (ViewHolder) convertView.getTag();
            }

            FileEntry entry = entries.get(position);
            vh.title.setText(entry.file.getName());

            if (entry.isDirectory) {
                vh.icon.setImageResource(R.drawable.ic_folder);
                vh.meta.setText("Folder");
                vh.subBadge.setVisibility(View.GONE);
                convertView.setAlpha(1.0f);
            } else if (entry.mediaItem != null) {
                MediaItem item = entry.mediaItem;
                vh.icon.setImageResource(item.isAudio ? R.drawable.ic_audio : R.drawable.ic_video);
                String kind = item.isAudio ? "Audio" : "Video";
                vh.meta.setText(kind + "  •  " + MediaItem.formatHumanSize(item.sizeBytes));
                if (item.hasSubtitle()) {
                    vh.subBadge.setVisibility(View.VISIBLE);
                    vh.subBadge.setText(item.subtitleType.toUpperCase(Locale.ROOT));
                } else {
                    vh.subBadge.setVisibility(View.GONE);
                }
                convertView.setAlpha(1.0f);
            } else {
                vh.icon.setImageResource(R.drawable.ic_file);
                vh.meta.setText(MediaItem.formatHumanSize(entry.file.length()) + "  •  Not selectable");
                vh.subBadge.setVisibility(View.GONE);
                convertView.setAlpha(0.42f);
            }
            return convertView;
        }
    }

    private static final class ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView meta;
        final TextView subBadge;

        ViewHolder(View root) {
            icon = root.findViewById(R.id.item_icon);
            title = root.findViewById(R.id.item_title);
            meta = root.findViewById(R.id.item_meta);
            subBadge = root.findViewById(R.id.item_sub_badge);
        }
    }
}
