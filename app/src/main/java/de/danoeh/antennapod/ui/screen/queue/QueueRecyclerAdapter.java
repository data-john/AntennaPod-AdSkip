package de.danoeh.antennapod.ui.screen.queue;

import android.annotation.SuppressLint;
import android.util.Log;
import android.view.ContextMenu;
import android.view.MenuInflater;
import android.view.MotionEvent;
import android.view.View;

import androidx.fragment.app.FragmentActivity;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import de.danoeh.antennapod.storage.preferences.AdSkipPreferences;
import de.danoeh.antennapod.ui.episodeslist.EpisodeItemListAdapter;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.swipeactions.SwipeActions;
import de.danoeh.antennapod.ui.episodeslist.EpisodeItemViewHolder;
import de.danoeh.antennapod.ui.adskip.AdSkipQueueStatus;
import java.util.Collections;
import java.util.Map;

/**
 * List adapter for the queue.
 */
public class QueueRecyclerAdapter extends EpisodeItemListAdapter {
    private static final String TAG = "QueueRecyclerAdapter";

    private final SwipeActions swipeActions;
    private boolean dragDropEnabled;
    private Map<Long, AdSkipQueueStatus> adSkipStatuses = Collections.emptyMap();


    public QueueRecyclerAdapter(FragmentActivity mainActivity, SwipeActions swipeActions) {
        super(mainActivity);
        this.swipeActions = swipeActions;
        dragDropEnabled = ! (UserPreferences.isQueueKeepSorted() || UserPreferences.isQueueLocked());
    }

    public void updateDragDropEnabled() {
        dragDropEnabled = ! (UserPreferences.isQueueKeepSorted() || UserPreferences.isQueueLocked());
        notifyDataSetChanged();
    }

    public void setAdSkipStatuses(Map<Long, AdSkipQueueStatus> statuses) {
        adSkipStatuses = statuses;
        notifyDataSetChanged();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void afterBindViewHolder(EpisodeItemViewHolder holder, int pos) {
        if (!dragDropEnabled) {
            holder.dragHandle.setVisibility(View.GONE);
            holder.dragHandle.setOnTouchListener(null);
            holder.coverHolder.setOnTouchListener(null);
        } else {
            holder.dragHandle.setVisibility(View.VISIBLE);
            holder.dragHandle.setOnTouchListener((v1, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    Log.d(TAG, "startDrag()");
                    swipeActions.startDrag(holder);
                }
                return false;
            });
            holder.coverHolder.setOnTouchListener((v1, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    boolean isLtr = holder.itemView.getLayoutDirection() == View.LAYOUT_DIRECTION_LTR;
                    float factor = isLtr ? 1 : -1;
                    if (factor * event.getX() < factor * 0.5 * v1.getWidth()) {
                        Log.d(TAG, "startDrag()");
                        swipeActions.startDrag(holder);
                    } else {
                        Log.d(TAG, "Ignoring drag in right half of the image");
                    }
                }
                return false;
            });
        }
        if (inActionMode()) {
            holder.dragHandle.setOnTouchListener(null);
            holder.coverHolder.setOnTouchListener(null);
        }

        holder.isInQueue.setVisibility(View.GONE);
        bindAdSkipStatus(holder);
    }

    private void bindAdSkipStatus(EpisodeItemViewHolder holder) {
        holder.adSkipStatusRow.setVisibility(View.GONE);
        holder.adSkipStageProgress.setVisibility(View.GONE);
        holder.adSkipStageProgress.setIndeterminate(false);
        holder.adSkipStageProgress.setProgressCompat(0, false);
        if (!AdSkipPreferences.showQueueStatus(getActivity())) {
            return;
        }
        FeedItem item = holder.getFeedItem();
        FeedMedia media = item == null ? null : item.getMedia();
        AdSkipQueueStatus status = media == null ? null : adSkipStatuses.get(media.getId());
        DownloadServiceInterface downloadService = DownloadServiceInterface.get();
        boolean downloadingSource = status != null && status.isWaitingForDownload()
                && downloadService != null
                && downloadService.isDownloadingEpisode(media.getDownloadUrl())
                && !downloadService.isEpisodeQueued(media.getDownloadUrl());
        if (downloadingSource) {
            status = AdSkipQueueStatus.sourceProgress();
        }
        if (status == null) {
            return;
        }
        String label = status.label(getActivity());
        holder.adSkipStatusIcon.setImageResource(status.iconResource());
        holder.adSkipStatusIcon.setContentDescription(label);
        holder.adSkipStatusText.setText(label);
        if (status.kind == AdSkipQueueStatus.Kind.IN_PROGRESS) {
            holder.adSkipStageProgress.setVisibility(View.VISIBLE);
            if (downloadingSource) {
                int progress = downloadService.getProgress(media.getDownloadUrl());
                holder.adSkipStageProgress.setIndeterminate(progress < 0);
                if (progress >= 0) {
                    holder.adSkipStageProgress.setProgressCompat(Math.min(progress, 100), false);
                }
            } else {
                holder.adSkipStageProgress.setIndeterminate(true);
            }
        }
        holder.adSkipStatusRow.setVisibility(View.VISIBLE);
    }

    @Override
    public void onCreateContextMenu(final ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        MenuInflater inflater = getActivity().getMenuInflater();
        inflater.inflate(R.menu.queue_context, menu);
        super.onCreateContextMenu(menu, v, menuInfo);

        if (!inActionMode()) {
            menu.findItem(R.id.multi_select).setVisible(true);
            final boolean keepSorted = UserPreferences.isQueueKeepSorted();
            if (getItem(0).getId() == getLongPressedItem().getId() || keepSorted) {
                menu.findItem(R.id.move_to_top_item).setVisible(false);
            }
            if (getItem(getItemCount() - 1).getId() == getLongPressedItem().getId() || keepSorted) {
                menu.findItem(R.id.move_to_bottom_item).setVisible(false);
            }
        } else {
            menu.findItem(R.id.move_to_top_item).setVisible(false);
            menu.findItem(R.id.move_to_bottom_item).setVisible(false);
        }
    }
}
