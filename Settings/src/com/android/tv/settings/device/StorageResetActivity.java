/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tv.settings.device;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.text.format.Formatter;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.Toast;

import com.android.settingslib.deviceinfo.StorageMeasurement;
import com.android.settingslib.deviceinfo.StorageMeasurement.MeasurementDetails;
import com.android.settingslib.deviceinfo.StorageMeasurement.MeasurementReceiver;
import com.android.tv.settings.R;
import com.android.tv.settings.device.apps.AppsActivity;
import com.android.tv.settings.device.storage.EjectInternalStepFragment;
import com.android.tv.settings.device.storage.FormatAsInternalStepFragment;
import com.android.tv.settings.dialog.Layout;
import com.android.tv.settings.dialog.Layout.Action;
import com.android.tv.settings.dialog.Layout.Header;
import com.android.tv.settings.dialog.Layout.Status;
import com.android.tv.settings.dialog.Layout.StringGetter;
import com.android.tv.settings.dialog.SettingsLayoutActivity;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Activity to view storage consumption and factory reset device.
 */
public class StorageResetActivity extends SettingsLayoutActivity {

    private static final String TAG = "StorageResetActivity";
    private static final long INVALID_SIZE = -1;
    private static final int ACTION_RESET_DEVICE = 1;
    private static final int ACTION_CANCEL = 2;
    private static final int ACTION_CLEAR_CACHE = 3;
    private static final int ACTION_EJECT_PRIVATE = 4;
    private static final int ACTION_EJECT_PUBLIC = 5;
    private static final int ACTION_ERASE_PRIVATE = 6;
    private static final int ACTION_ERASE_PUBLIC = 7;

    /**
     * Support for shutdown-after-reset. If our launch intent has a true value for
     * the boolean extra under the following key, then include it in the intent we
     * use to trigger a factory reset. This will cause us to shut down instead of
     * restart after the reset.
     */
    private static final String SHUTDOWN_INTENT_EXTRA = "shutdown";

    private class SizeStringGetter extends StringGetter {
        private long mSize = INVALID_SIZE;

        @Override
        public String get() {
            return String.format(getString(R.string.storage_size), formatSize(mSize));
        }

        public void setSize(long size) {
            mSize = size;
            refreshView();
        }
    }

    private StorageManager mStorageManager;

    private final Map<String, StorageLayoutGetter> mStorageLayoutGetters = new ArrayMap<>();
    private final Map<String, SizeStringGetter> mStorageDescriptionGetters = new ArrayMap<>();

    private final StorageEventListener mStorageListener = new StorageEventListener() {
        @Override
        public void onVolumeStateChanged(VolumeInfo vol, int oldState, int newState) {
            switch(vol.getType()) {
                case VolumeInfo.TYPE_PRIVATE:
                case VolumeInfo.TYPE_PUBLIC:
                    mStorageHeadersGetter.refreshView();
                    StorageLayoutGetter getter = mStorageLayoutGetters.get(vol.getId());
                    if (getter != null) {
                        getter.onVolumeUpdated();
                    }
                    break;
                default:
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mStorageManager = getSystemService(StorageManager.class);
        mStorageHeadersGetter.refreshView();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mStorageManager.registerListener(mStorageListener);
        for (StorageLayoutGetter getter : mStorageLayoutGetters.values()) {
            getter.startListening();
        }
    }

    @Override
    protected void onPause() {
        mStorageManager.unregisterListener(mStorageListener);
        for (StorageLayoutGetter getter : mStorageLayoutGetters.values()) {
            getter.stopListening();
        }
        super.onPause();
    }

    @Override
    public Layout createLayout() {
        return new Layout().breadcrumb(getString(R.string.header_category_device))
                .add(new Header.Builder(getResources())
                        .icon(R.drawable.ic_settings_storage)
                        .title(R.string.device_storage_reset)
                        .build()
                        .add(mStorageHeadersGetter)
                        .add(createResetHeaders())
                );
    }

    private final Layout.LayoutGetter mStorageHeadersGetter = new Layout.LayoutGetter() {
        @Override
        public Layout get() {
            final Resources res = getResources();
            final Layout layout = new Layout();
            if (mStorageManager == null) {
                return layout;
            }
            final List<VolumeInfo> volumes = mStorageManager.getVolumes();
            Collections.sort(volumes, VolumeInfo.getDescriptionComparator());

            for (VolumeInfo vol : volumes) {
                if (vol.getType() != VolumeInfo.TYPE_PRIVATE
                        && vol.getType() != VolumeInfo.TYPE_PUBLIC) {
                    continue;
                }
                final String volId = vol.getId();
                StorageLayoutGetter storageGetter = mStorageLayoutGetters.get(volId);
                if (storageGetter == null) {
                    storageGetter = new StorageLayoutGetter(vol);
                    mStorageLayoutGetters.put(volId, storageGetter);
                    if (isResumed()) {
                        storageGetter.startListening();
                    }
                }
                SizeStringGetter sizeGetter = mStorageDescriptionGetters.get(volId);
                if (sizeGetter == null) {
                    sizeGetter = new SizeStringGetter();
                    mStorageDescriptionGetters.put(volId, sizeGetter);
                }
                final File path = vol.getPath();
                if (path != null) {
                    // TODO: something more dynamic here
                    sizeGetter.setSize(path.getTotalSpace());
                }
                final Header header = new Header.Builder(res)
                        .title(mStorageManager.getBestVolumeDescription(vol))
                        .description(sizeGetter)
                        .build().add(storageGetter);
                layout.add(header);
            }
            return layout;
        }
    };

    private class StorageLayoutGetter extends Layout.LayoutGetter {

        private final String mVolumeId;
        private final String mVolumeDescription;

        private StorageMeasurement mMeasure;
        private final SizeStringGetter mAppsSize = new SizeStringGetter();
        private final SizeStringGetter mDcimSize = new SizeStringGetter();
        private final SizeStringGetter mMusicSize = new SizeStringGetter();
        private final SizeStringGetter mDownloadsSize = new SizeStringGetter();
        private final SizeStringGetter mCacheSize = new SizeStringGetter();
        private final SizeStringGetter mMiscSize = new SizeStringGetter();
        private final SizeStringGetter mAvailSize = new SizeStringGetter();

        private final MeasurementReceiver mReceiver = new MeasurementReceiver() {

            private MeasurementDetails mLastMeasurementDetails = null;

            @Override
            public void onDetailsChanged(MeasurementDetails details) {
                mLastMeasurementDetails = details;
                updateDetails(mLastMeasurementDetails);
            }
        };

        public StorageLayoutGetter(VolumeInfo volume) {
            mVolumeId = volume.getId();
            mVolumeDescription = mStorageManager.getBestVolumeDescription(volume);
        }

        @Override
        public Layout get() {
            final Resources res = getResources();
            final Layout layout = new Layout();

            final Bundle data = new Bundle(1);
            data.putString(VolumeInfo.EXTRA_VOLUME_ID, mVolumeId);

            final VolumeInfo volume = mStorageManager.findVolumeById(mVolumeId);

            if (volume == null) {
                layout
                        .add(new Status.Builder(res)
                                .title(R.string.storage_not_connected)
                                .build());
            } else if (volume.getType() == VolumeInfo.TYPE_PRIVATE) {
                if (!VolumeInfo.ID_PRIVATE_INTERNAL.equals(mVolumeId)) {
                    layout
                            .add(new Action.Builder(res, ACTION_EJECT_PRIVATE)
                                    .title(R.string.storage_eject)
                                    .data(data)
                                    .build())
                            .add(new Action.Builder(res, ACTION_ERASE_PRIVATE)
                                    .title(R.string.storage_format)
                                    .data(data)
                                    .build());
                }
                layout
                        .add(new Action.Builder(res,
                                new Intent(StorageResetActivity.this, AppsActivity.class))
                                .title(R.string.storage_apps_usage)
                                .icon(R.drawable.storage_indicator_apps)
                                .description(mAppsSize)
                                .build())
                        .add(new Status.Builder(res)
                                .title(R.string.storage_dcim_usage)
                                .icon(R.drawable.storage_indicator_dcim)
                                .description(mDcimSize)
                                .build())
                        .add(new Status.Builder(res)
                                .title(R.string.storage_music_usage)
                                .icon(R.drawable.storage_indicator_music)
                                .description(mMusicSize)
                                .build())
                        .add(new Status.Builder(res)
                                .title(R.string.storage_downloads_usage)
                                .icon(R.drawable.storage_indicator_downloads)
                                .description(mDownloadsSize)
                                .build())
                        .add(new Action.Builder(res, ACTION_CLEAR_CACHE)
                                .title(R.string.storage_media_cache_usage)
                                .icon(R.drawable.storage_indicator_cache)
                                .description(mCacheSize)
                                .build())
                        .add(new Status.Builder(res)
                                .title(R.string.storage_media_misc_usage)
                                .icon(R.drawable.storage_indicator_misc)
                                .description(mMiscSize)
                                .build())
                        .add(new Status.Builder(res)
                                .title(R.string.storage_available)
                                .icon(R.drawable.storage_indicator_available)
                                .description(mAvailSize)
                                .build());
            } else {
                if (volume.getState() == VolumeInfo.STATE_UNMOUNTED) {
                    layout
                            .add(new Status.Builder(res)
                                    .title(getString(R.string.storage_unmount_success,
                                            mVolumeDescription))
                                    .build());
                } else {
                    layout
                            .add(new Action.Builder(res, ACTION_EJECT_PUBLIC)
                                    .title(R.string.storage_eject)
                                    .data(data)
                                    .build())
                            .add(new Action.Builder(res, ACTION_ERASE_PUBLIC)
                                    .title(R.string.storage_format_for_private)
                                    .data(data)
                                    .build())
                            .add(new Status.Builder(res)
                                    .title(R.string.storage_media_misc_usage)
                                    .icon(R.drawable.storage_indicator_misc)
                                    .description(mMiscSize)
                                    .build())
                            .add(new Status.Builder(res)
                                    .title(R.string.storage_available)
                                    .icon(R.drawable.storage_indicator_available)
                                    .description(mAvailSize)
                                    .build());
                }
            }
            return layout;
        }

        public void onVolumeUpdated() {
            stopListening();
            startListening();
            refreshView();
        }

        public void startListening() {
            final VolumeInfo volume = mStorageManager.findVolumeById(mVolumeId);
            if (volume != null && volume.isMountedReadable()) {
                final VolumeInfo sharedVolume = mStorageManager.findEmulatedForPrivate(volume);
                mMeasure = new StorageMeasurement(StorageResetActivity.this, volume,
                        sharedVolume);
                mMeasure.setReceiver(mReceiver);
                mMeasure.forceMeasure();
            }
        }

        public void stopListening() {
            if (mMeasure != null) {
                mMeasure.onDestroy();
            }
        }

        private void updateDetails(MeasurementDetails details) {
            final long dcimSize = totalValues(details.mediaSize, Environment.DIRECTORY_DCIM,
                    Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_PICTURES);

            final long musicSize = totalValues(details.mediaSize, Environment.DIRECTORY_MUSIC,
                    Environment.DIRECTORY_ALARMS, Environment.DIRECTORY_NOTIFICATIONS,
                    Environment.DIRECTORY_RINGTONES, Environment.DIRECTORY_PODCASTS);

            final long downloadsSize = totalValues(details.mediaSize, Environment.DIRECTORY_DOWNLOADS);

            mAvailSize.setSize(details.availSize);
            mAppsSize.setSize(details.appsSize);
            mDcimSize.setSize(dcimSize);
            mMusicSize.setSize(musicSize);
            mDownloadsSize.setSize(downloadsSize);
            mCacheSize.setSize(details.cacheSize);
            mMiscSize.setSize(details.miscSize);
        }
    }

    private Header createResetHeaders() {
        final Resources res = getResources();
        return new Header.Builder(res)
                .title(R.string.device_reset)
                .build()
                .add(new Header.Builder(res)
                        .title(R.string.device_reset)
                        .build()
                        .add(new Action.Builder(res, ACTION_RESET_DEVICE)
                                .title(R.string.confirm_factory_reset_device)
                                .build()
                        )
                        .add(new Action.Builder(res, Action.ACTION_BACK)
                                .title(R.string.title_cancel)
                                .defaultSelection()
                                .build())
                )
                .add(new Action.Builder(res, Action.ACTION_BACK)
                        .title(R.string.title_cancel)
                        .defaultSelection()
                        .build());
    }

    @Override
    public void onActionClicked(Action action) {
        switch (action.getId()) {
            case ACTION_RESET_DEVICE:
                if (!ActivityManager.isUserAMonkey()) {
                    Intent resetIntent = new Intent("android.intent.action.MASTER_CLEAR");
                    if (getIntent().getBooleanExtra(SHUTDOWN_INTENT_EXTRA, false)) {
                        resetIntent.putExtra(SHUTDOWN_INTENT_EXTRA, true);
                    }
                    sendBroadcast(resetIntent);
                }
                break;
            case ACTION_CANCEL:
                goBackToTitle(getString(R.string.device_storage_reset));
                break;
            case ACTION_CLEAR_CACHE:
                final DialogFragment fragment = ConfirmClearCacheFragment.newInstance();
                fragment.show(getFragmentManager(), null);
                break;
            case ACTION_EJECT_PUBLIC:
                new UnmountTask(this, mStorageManager.findVolumeById(
                        action.getData().getString(VolumeInfo.EXTRA_VOLUME_ID)))
                        .execute();
                break;
            case ACTION_EJECT_PRIVATE: {
                final Fragment f =
                        EjectInternalStepFragment.newInstance(mStorageManager.findVolumeById(
                                action.getData().getString(VolumeInfo.EXTRA_VOLUME_ID)));
                getFragmentManager().beginTransaction()
                        .replace(android.R.id.content, f)
                        .addToBackStack(null)
                        .commit();
                break;
            }
            case ACTION_ERASE_PUBLIC: {
                final Fragment f =
                        FormatAsInternalStepFragment.newInstance(mStorageManager.findVolumeById(
                                action.getData().getString(VolumeInfo.EXTRA_VOLUME_ID)));
                getFragmentManager().beginTransaction()
                        .replace(android.R.id.content, f)
                        .addToBackStack(null)
                        .commit();
                break;
            }
            case ACTION_ERASE_PRIVATE:
                break;
            default:
                final Intent intent = action.getIntent();
                if (intent != null) {
                    startActivity(intent);
                }
        }
    }

    private static long totalValues(HashMap<String, Long> map, String... keys) {
        long total = 0;
        for (String key : keys) {
            if (map.containsKey(key)) {
                total += map.get(key);
            }
        }
        return total;
    }

    private String formatSize(long size) {
        return (size == INVALID_SIZE) ? getString(R.string.storage_calculating_size)
                : Formatter.formatShortFileSize(this, size);
    }

    /**
     * Dialog to request user confirmation before clearing all cache data.
     */
    public static class ConfirmClearCacheFragment extends DialogFragment {
        public static ConfirmClearCacheFragment newInstance() {
            return new ConfirmClearCacheFragment();
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Context context = getActivity();

            final AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(R.string.device_storage_clear_cache_title);
            builder.setMessage(getString(R.string.device_storage_clear_cache_message));

            builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    final PackageManager pm = context.getPackageManager();
                    final List<PackageInfo> infos = pm.getInstalledPackages(0);
                    for (PackageInfo info : infos) {
                        pm.deleteApplicationCacheFiles(info.packageName, null);
                    }
                }
            });
            builder.setNegativeButton(android.R.string.cancel, null);

            return builder.create();
        }
    }

    public static class MountTask extends AsyncTask<Void, Void, Exception> {
        private final Context mContext;
        private final StorageManager mStorageManager;
        private final String mVolumeId;
        private final String mDescription;

        public MountTask(Context context, VolumeInfo volume) {
            mContext = context.getApplicationContext();
            mStorageManager = mContext.getSystemService(StorageManager.class);
            mVolumeId = volume.getId();
            mDescription = mStorageManager.getBestVolumeDescription(volume);
        }

        @Override
        protected Exception doInBackground(Void... params) {
            try {
                mStorageManager.mount(mVolumeId);
                return null;
            } catch (Exception e) {
                return e;
            }
        }

        @Override
        protected void onPostExecute(Exception e) {
            if (e == null) {
                Toast.makeText(mContext, mContext.getString(R.string.storage_mount_success,
                        mDescription), Toast.LENGTH_SHORT).show();
            } else {
                Log.e(TAG, "Failed to mount " + mVolumeId, e);
                Toast.makeText(mContext, mContext.getString(R.string.storage_mount_failure,
                        mDescription), Toast.LENGTH_SHORT).show();
            }
        }
    }

    public static class UnmountTask extends AsyncTask<Void, Void, Exception> {
        private final Context mContext;
        private final StorageManager mStorageManager;
        private final String mVolumeId;
        private final String mDescription;

        public UnmountTask(Context context, VolumeInfo volume) {
            mContext = context.getApplicationContext();
            mStorageManager = mContext.getSystemService(StorageManager.class);
            mVolumeId = volume.getId();
            mDescription = mStorageManager.getBestVolumeDescription(volume);
        }

        @Override
        protected Exception doInBackground(Void... params) {
            try {
                mStorageManager.unmount(mVolumeId);
                return null;
            } catch (Exception e) {
                return e;
            }
        }

        @Override
        protected void onPostExecute(Exception e) {
            if (e == null) {
                Toast.makeText(mContext, mContext.getString(R.string.storage_unmount_success,
                        mDescription), Toast.LENGTH_SHORT).show();
            } else {
                Log.e(TAG, "Failed to unmount " + mVolumeId, e);
                Toast.makeText(mContext, mContext.getString(R.string.storage_unmount_failure,
                        mDescription), Toast.LENGTH_SHORT).show();
            }
        }
    }

    public static class FormatAsPrivateTask extends AsyncTask<Void, Void, Exception> {
        private final Context mContext;
        private final StorageManager mStorageManager;
        private final String mDiskId;
        private final String mDescription;

        public FormatAsPrivateTask(Context context, VolumeInfo volume) {
            mContext = context.getApplicationContext();
            mStorageManager = mContext.getSystemService(StorageManager.class);
            mDiskId = volume.getDiskId();
            mDescription = mStorageManager.getBestVolumeDescription(volume);
        }

        @Override
        protected Exception doInBackground(Void... params) {
            try {
                mStorageManager.partitionPrivate(mDiskId);
                return null;
            } catch (Exception e) {
                return e;
            }
        }

        @Override
        protected void onPostExecute(Exception e) {
            if (e == null) {
                Toast.makeText(mContext, mContext.getString(R.string.storage_format_success,
                        mDescription), Toast.LENGTH_SHORT).show();
            } else {
                Log.e(TAG, "Failed to format " + mDiskId, e);
                Toast.makeText(mContext, mContext.getString(R.string.storage_format_failure,
                        mDescription), Toast.LENGTH_SHORT).show();
            }
        }
    }

}
