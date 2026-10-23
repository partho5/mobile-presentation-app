package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.entitlements.Entitlements;
import com.jovoc.facecampresentationrecorder.entitlements.Feature;
import com.jovoc.facecampresentationrecorder.publish.Platforms;
import com.jovoc.facecampresentationrecorder.publish.Platforms.ChannelState;
import com.jovoc.facecampresentationrecorder.publish.Platforms.Platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The platform list of the Auto upload screen: a "Video platforms" section, then "Other
 * platforms", each platform followed by its connected channels. Taps go to {@link Listener}.
 */
final class PlatformListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface Listener {
        /** A platform that is not connected was tapped, a channel asked to be reconnected, or
         * "Add channel" was tapped. */
        void onPlatformAction(Platform platform);

        /** The user flipped a channel's auto-publish switch. */
        void onChannelToggled(ChannelState channel, boolean enabled);

        /** The user flipped a channel's "Text post from your video" switch. */
        void onTextPostToggled(ChannelState channel, boolean enabled);
    }

    private static final int TYPE_HEADER = 0, TYPE_PLATFORM = 1, TYPE_CHANNEL = 2, TYPE_ADD = 3;
    private static final int COLOR_GREY = 0xFF888888;
    private static final int COLOR_AMBER = 0xFFFFB347;

    /** One row: a section title, a platform, or a channel of {@link #platform}. */
    private static final class Item {
        final int type;
        final int titleRes;
        final Platform platform;
        final ChannelState channel;

        Item(int type, int titleRes, Platform platform, ChannelState channel) {
            this.type = type;
            this.titleRes = titleRes;
            this.platform = platform;
            this.channel = channel;
        }
    }

    private final Listener listener;
    /** Ids of channels whose switch is being saved (owned by the activity, read live). */
    private final Set<String> savingIds;
    private final List<Item> items = new ArrayList<>();
    /** The platform whose web link is being requested; its row shows a spinner. */
    private String busyIdentifier;

    PlatformListAdapter(Listener listener, Set<String> savingIds) {
        this.listener = listener;
        this.savingIds = savingIds;
    }

    /** Video platforms first, then the rest, each in server order. */
    void submit(Platforms platforms) {
        items.clear();
        addSection(platforms, true, R.string.ap_section_video);
        addSection(platforms, false, R.string.ap_section_other);
        notifyDataSetChanged();
    }

    private void addSection(Platforms platforms, boolean video, int titleRes) {
        boolean titled = false;
        for (Platform p : platforms.platforms) {
            if (p.isVideo() != video) continue;
            if (!titled) {
                items.add(new Item(TYPE_HEADER, titleRes, null, null));
                titled = true;
            }
            items.add(new Item(TYPE_PLATFORM, 0, p, null));
            if (!p.isConnected()) continue;
            for (ChannelState c : p.channels) items.add(new Item(TYPE_CHANNEL, 0, p, c));
            items.add(new Item(TYPE_ADD, 0, p, null));
        }
    }

    void setBusy(String identifier) {
        busyIdentifier = identifier;
        notifyItemRangeChanged(0, items.size());
    }

    /** The plan changed: locked rows must look locked (or open) again. */
    void refreshGate() {
        notifyItemRangeChanged(0, items.size());
    }

    /** Redraws the row of one channel (its switch was flipped or its save finished). */
    void notifyChannel(String channelId) {
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.channel != null && it.channel.id.equals(channelId)) notifyItemChanged(i);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_HEADER:
                return new HeaderHolder(inflater.inflate(R.layout.item_ap_header, parent, false));
            case TYPE_PLATFORM:
                return new PlatformHolder(inflater.inflate(R.layout.item_ap_platform, parent, false));
            case TYPE_ADD:
                return new AddHolder(inflater.inflate(R.layout.item_ap_add, parent, false));
            default:
                return new ChannelHolder(inflater.inflate(R.layout.item_ap_channel, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Item it = items.get(position);
        if (holder instanceof HeaderHolder) {
            ((HeaderHolder) holder).title.setText(it.titleRes);
        } else if (holder instanceof PlatformHolder) {
            bindPlatform((PlatformHolder) holder, it.platform);
        } else if (holder instanceof AddHolder) {
            bindAdd((AddHolder) holder, it.platform);
        } else {
            bindChannel((ChannelHolder) holder, it.platform, it.channel);
        }
    }

    private void bindAdd(AddHolder h, Platform p) {
        Context c = h.itemView.getContext();
        boolean busy = p.identifier.equals(busyIdentifier);
        h.label.setText(c.getString(p.channels.isEmpty() ? R.string.ap_add_first_channel
                : R.string.ap_add_channel, p.name));
        h.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        h.itemView.setOnClickListener(v -> listener.onPlatformAction(p));
    }

    private void bindPlatform(PlatformHolder h, Platform p) {
        Context c = h.itemView.getContext();
        h.name.setText(p.name);
        int radius = Math.round(8 * c.getResources().getDisplayMetrics().density);
        if (p.icon.isEmpty()) {
            Glide.with(h.icon).clear(h.icon);
            h.icon.setImageResource(R.drawable.bg_ap_icon_placeholder);
        } else {
            Glide.with(h.icon).load(p.icon)
                    .transform(new CenterCrop(), new RoundedCorners(radius))
                    .placeholder(R.drawable.bg_ap_icon_placeholder)
                    .error(R.drawable.bg_ap_icon_placeholder)
                    .into(h.icon);
        }

        boolean busy = p.identifier.equals(busyIdentifier);
        h.connected.setVisibility(p.isConnected() && !busy ? View.VISIBLE : View.GONE);
        h.state.setVisibility(!p.isConnected() && !busy ? View.VISIBLE : View.GONE);
        h.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (p.isConnected()) {
            // Connected rows are static; their channels carry the actions.
            h.itemView.setOnClickListener(null);
            h.itemView.setClickable(false);
        } else {
            h.itemView.setOnClickListener(v -> listener.onPlatformAction(p));
        }
    }

    private void bindChannel(ChannelHolder h, Platform p, ChannelState ch) {
        Context c = h.itemView.getContext();
        String name = ch.name.isEmpty() ? p.name : ch.name;
        h.name.setText(name);
        if (ch.picture == null) {
            Glide.with(h.avatar).clear(h.avatar);
            h.avatar.setImageResource(R.drawable.bg_ap_icon_placeholder);
        } else {
            Glide.with(h.avatar).load(ch.picture).circleCrop()
                    .placeholder(R.drawable.bg_ap_icon_placeholder)
                    .error(R.drawable.bg_ap_icon_placeholder)
                    .into(h.avatar);
        }

        // Rebinding must not fire the listener, nor animate a recycled switch.
        h.toggle.setOnCheckedChangeListener(null);
        h.itemView.setOnClickListener(null);
        h.itemView.setClickable(false);

        String state = null;
        int stateColor = COLOR_GREY;
        if (ch.refreshNeeded) {
            state = c.getString(R.string.ap_reconnect);
            stateColor = COLOR_AMBER;
            h.itemView.setOnClickListener(v -> listener.onPlatformAction(p));
        } else if (ch.disabled) {
            state = c.getString(R.string.ap_paused);
        } else if (p.channelRow() == Platforms.ChannelRow.MANUAL_ONLY) {
            state = c.getString(R.string.ap_manual_only);
        }
        boolean textRow = state == null && p.channelRow() == Platforms.ChannelRow.TEXT_POST;
        // The crown (muted when unlocked, gold when locked) only on the text-post label.
        if (textRow) {
            h.state.setText(R.string.ap_text_post);
            h.state.setTextColor(COLOR_GREY);
            PremiumMark.apply(h.state, Feature.AI_CAPTIONS);
        } else {
            h.state.setCompoundDrawablesRelative(null, null, null, null);
        }

        if (textRow) {
            bindTextSwitch(h, name, ch);
            return;
        }
        if (state != null) {
            h.state.setText(state);
            h.state.setTextColor(stateColor);
            h.state.setVisibility(View.VISIBLE);
            h.toggle.setVisibility(View.GONE);
            h.boundId = null;
            return;
        }
        h.state.setVisibility(View.GONE);
        h.toggle.setVisibility(View.VISIBLE);
        h.toggle.setContentDescription(c.getString(R.string.ap_channel_switch_cd, name));
        h.toggle.setChecked(ch.autoPublish);
        // A recycled row snaps to its state; the same row keeps the flip animation running.
        if (!ch.id.equals(h.boundId)) h.toggle.jumpDrawablesToCurrentState();
        h.boundId = ch.id;
        boolean saving = savingIds.contains(ch.id);
        h.toggle.setEnabled(!saving);
        h.toggle.setOnCheckedChangeListener((b, on) -> listener.onChannelToggled(ch, on));
        // The whole row is the touch target, not just the small switch.
        if (!saving) h.itemView.setOnClickListener(v -> h.toggle.toggle());
    }

    /** The text-post switch: off while the plan lacks it (a tap opens the upgrade sheet). */
    private void bindTextSwitch(ChannelHolder h, String name, ChannelState ch) {
        Context c = h.itemView.getContext();
        h.state.setVisibility(View.VISIBLE);
        h.toggle.setVisibility(View.VISIBLE);
        h.toggle.setContentDescription(c.getString(R.string.ap_text_post_switch_cd, name));
        h.toggle.setChecked(ch.textPostOn && Entitlements.has(Feature.AI_CAPTIONS));
        if (!ch.id.equals(h.boundId)) h.toggle.jumpDrawablesToCurrentState();
        h.boundId = ch.id;
        boolean saving = savingIds.contains(ch.id);
        h.toggle.setEnabled(!saving);
        h.toggle.setOnCheckedChangeListener((b, on) -> listener.onTextPostToggled(ch, on));
        if (!saving) h.itemView.setOnClickListener(v -> h.toggle.toggle());
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;

        HeaderHolder(View v) {
            super(v);
            title = v.findViewById(R.id.tv_ap_header);
        }
    }

    private static final class PlatformHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final ImageView connected;
        final TextView state;
        final ProgressBar progress;

        PlatformHolder(View v) {
            super(v);
            icon = v.findViewById(R.id.iv_ap_platform_icon);
            name = v.findViewById(R.id.tv_ap_platform_name);
            connected = v.findViewById(R.id.iv_ap_platform_connected);
            state = v.findViewById(R.id.tv_ap_platform_state);
            progress = v.findViewById(R.id.progress_ap_platform);
        }
    }

    private static final class AddHolder extends RecyclerView.ViewHolder {
        final TextView label;
        final ProgressBar progress;

        AddHolder(View v) {
            super(v);
            label = v.findViewById(R.id.tv_ap_add);
            progress = v.findViewById(R.id.progress_ap_add);
        }
    }

    private static final class ChannelHolder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView name;
        final TextView state;
        final SwitchCompat toggle;
        /** The channel this row showed last. */
        String boundId;

        ChannelHolder(View v) {
            super(v);
            avatar = v.findViewById(R.id.iv_ap_channel_avatar);
            name = v.findViewById(R.id.tv_ap_channel_name);
            state = v.findViewById(R.id.tv_ap_channel_state);
            toggle = v.findViewById(R.id.switch_ap_channel);
        }
    }
}
