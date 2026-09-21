package com.customscreen.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.customscreen.app.R;
import com.customscreen.app.db.Slide;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SlideAdapter extends RecyclerView.Adapter<SlideAdapter.SlideViewHolder> {

    private List<Slide> slides = new ArrayList<>();
    private final SlideActionListener listener;

    public interface SlideActionListener {
        void onSlideClick(Slide slide, int position);
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
        }

        // Move Up / Down button state
        holder.btnMoveUp.setEnabled(position > 0);
        holder.btnMoveUp.setAlpha(position > 0 ? 1.0f : 0.3f);
        holder.btnMoveDown.setEnabled(position < slides.size() - 1);
        holder.btnMoveDown.setAlpha(position < slides.size() - 1 ? 1.0f : 0.3f);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onSlideClick(slide, holder.getAdapterPosition());
        });

        holder.btnMoveUp.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (listener != null && pos > 0) listener.onMoveUp(pos);
        });

        holder.btnMoveDown.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (listener != null && pos < slides.size() - 1) listener.onMoveDown(pos);
        });

        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION) {
                listener.onDelete(slide, pos);
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
        ImageButton btnMoveUp;
        ImageButton btnMoveDown;
        ImageButton btnDelete;

        public SlideViewHolder(@NonNull View itemView) {
            super(itemView);
            slideNumber = itemView.findViewById(R.id.slide_number);
            slideTypeBadge = itemView.findViewById(R.id.slide_type_badge);
            slidePreviewText = itemView.findViewById(R.id.slide_preview_text);
            slideThumbnail = itemView.findViewById(R.id.slide_thumbnail);
            btnMoveUp = itemView.findViewById(R.id.btn_move_up);
            btnMoveDown = itemView.findViewById(R.id.btn_move_down);
            btnDelete = itemView.findViewById(R.id.btn_delete_slide);
        }
    }
}
