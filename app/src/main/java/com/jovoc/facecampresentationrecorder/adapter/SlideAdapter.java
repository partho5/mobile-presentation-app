package com.jovoc.facecampresentationrecorder.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.db.Slide;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SlideAdapter extends RecyclerView.Adapter<SlideAdapter.SlideViewHolder> {

    private List<Slide> slides = new ArrayList<>();
    private final SlideActionListener listener;

    public interface SlideActionListener {
        void onSlideClick(Slide slide, int position);
        void onEditSlide(Slide slide, int position);
        void onMoveUp(int position);
        void onMoveDown(int position);
        void onDelete(Slide slide, int position);
    }

    public SlideAdapter(SlideActionListener listener) {
        this.listener = listener;
    }

    public void setSlides(List<Slide> slides) {
        this.slides = slides != null ? slides : new ArrayList<>();
        notifyDataSetChanged();
    }

    public List<Slide> getSlides() {
        return slides;
    }

    public void onItemMove(int fromPosition, int toPosition) {
        if (fromPosition < 0 || toPosition < 0 || fromPosition >= slides.size() || toPosition >= slides.size()) {
            return;
        }
        if (fromPosition < toPosition) {
            for (int i = fromPosition; i < toPosition; i++) {
                Collections.swap(slides, i, i + 1);
            }
        } else {
            for (int i = fromPosition; i > toPosition; i--) {
                Collections.swap(slides, i, i - 1);
            }
        }
        notifyItemMoved(fromPosition, toPosition);
    }

    @NonNull
    @Override
    public SlideViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_slide, parent, false);
        return new SlideViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SlideViewHolder holder, int position) {
        Slide slide = slides.get(position);
        holder.slideNumber.setText(String.valueOf(position + 1));
        holder.slideTypeBadge.setText(slide.getType());

        if (Slide.TYPE_TEXT.equals(slide.getType())) {
            holder.slideThumbnail.setVisibility(View.GONE);
            holder.slidePreviewText.setText(slide.getTextContent() != null ? slide.getTextContent() : "(Empty Text)");
        } else if (Slide.TYPE_IMAGE.equals(slide.getType())) {
            holder.slideThumbnail.setVisibility(View.VISIBLE);
            holder.slidePreviewText.setText("Image Slide");
            if (slide.getImagePath() != null) {
                Glide.with(holder.itemView.getContext())
                        .load(new File(slide.getImagePath()))
                        .centerCrop()
                        .into(holder.slideThumbnail);
            }
        } else if (Slide.TYPE_VIDEO.equals(slide.getType())) {
            holder.slideThumbnail.setVisibility(View.VISIBLE);
            holder.slidePreviewText.setText("Video Slide");
            if (slide.getImagePath() != null) {
                Glide.with(holder.itemView.getContext())
                        .load(new File(slide.getImagePath()))
                        .centerCrop()
                        .into(holder.slideThumbnail);
            } else {
                holder.slideThumbnail.setImageResource(R.drawable.ic_video_library);
            }
        } else if (Slide.TYPE_WEBSITE.equals(slide.getType())) {
            holder.slideThumbnail.setVisibility(View.VISIBLE);
            holder.slideThumbnail.setImageResource(R.drawable.ic_web);
            holder.slidePreviewText.setText(slide.getTextContent() != null ? slide.getTextContent() : "Website Slide");
        } else if (Slide.TYPE_YOUTUBE.equals(slide.getType())) {
            holder.slideThumbnail.setVisibility(View.VISIBLE);
            holder.slideThumbnail.setImageResource(R.drawable.ic_youtube);
            holder.slidePreviewText.setText(slide.getTextContent() != null ? slide.getTextContent() : "YouTube Video");
        }

        // Disabled state visual handling
        if (slide.isDisabled()) {
            holder.slideNumber.setAlpha(0.45f);
            holder.slideTypeBadge.setAlpha(0.45f);
            holder.slidePreviewText.setAlpha(0.45f);
            holder.slideThumbnail.setAlpha(0.45f);
            holder.disabledBadge.setVisibility(View.VISIBLE);
            holder.btnEdit.setAlpha(0.45f);
            // Delete button stays fully visible
            holder.btnDelete.setAlpha(1.0f);
            // Move buttons disabled for disabled slides
            holder.btnMoveUp.setEnabled(false);
            holder.btnMoveUp.setAlpha(0.3f);
            holder.btnMoveDown.setEnabled(false);
            holder.btnMoveDown.setAlpha(0.3f);
        } else {
            holder.slideNumber.setAlpha(1.0f);
            holder.slideTypeBadge.setAlpha(1.0f);
            holder.slidePreviewText.setAlpha(1.0f);
            holder.slideThumbnail.setAlpha(1.0f);
            holder.disabledBadge.setVisibility(View.GONE);
            holder.btnEdit.setAlpha(1.0f);
            holder.btnDelete.setAlpha(1.0f);
        }

        // Move Up / Down button state (only for active slides; disabled slides handled above)
        if (!slide.isDisabled()) {
            holder.btnMoveUp.setEnabled(position > 0);
            holder.btnMoveUp.setAlpha(position > 0 ? 1.0f : 0.3f);
            holder.btnMoveDown.setEnabled(position < slides.size() - 1);
            holder.btnMoveDown.setAlpha(position < slides.size() - 1 ? 1.0f : 0.3f);
        }

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION && pos < slides.size()) {
                listener.onSlideClick(slides.get(pos), pos);
            }
        });

        holder.btnEdit.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION && pos < slides.size()) {
                listener.onEditSlide(slides.get(pos), pos);
            }
        });

        holder.btnMoveUp.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION && pos > 0 && pos < slides.size()) {
                listener.onMoveUp(pos);
            }
        });

        holder.btnMoveDown.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION && pos >= 0 && pos < slides.size() - 1) {
                listener.onMoveDown(pos);
            }
        });

        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION && pos < slides.size()) {
                listener.onDelete(slides.get(pos), pos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return slides.size();
    }

    static class SlideViewHolder extends RecyclerView.ViewHolder {
        TextView slideNumber;
        TextView slideTypeBadge;
        TextView slidePreviewText;
        ImageView slideThumbnail;
        ImageButton btnEdit;
        ImageButton btnMoveUp;
        ImageButton btnMoveDown;
        ImageButton btnDelete;
        TextView disabledBadge;

        public SlideViewHolder(@NonNull View itemView) {
            super(itemView);
            slideNumber = itemView.findViewById(R.id.slide_number);
            slideTypeBadge = itemView.findViewById(R.id.slide_type_badge);
            slidePreviewText = itemView.findViewById(R.id.slide_preview_text);
            slideThumbnail = itemView.findViewById(R.id.slide_thumbnail);
            btnEdit = itemView.findViewById(R.id.btn_edit_slide);
            btnMoveUp = itemView.findViewById(R.id.btn_move_up);
            btnMoveDown = itemView.findViewById(R.id.btn_move_down);
            btnDelete = itemView.findViewById(R.id.btn_delete_slide);
            disabledBadge = itemView.findViewById(R.id.slide_disabled_badge);
        }
    }
}
