package com.example.rumiologia;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.rumiologia.fichas.ImagenesEquipos;

import java.util.ArrayList;
import java.util.List;

/**
 * Tira horizontal de los equipos detectados en el frame actual, estilo Historias:
 * un círculo por clase con su foto, tocar uno abre el mismo modal que tocar la caja
 * en {@link OverlayView}. Sigue el mismo patrón que {@code ChatAdapter}.
 */
public class AdaptadorChipsEquipo extends RecyclerView.Adapter<AdaptadorChipsEquipo.Celda> {

    public interface OnChipClickListener {
        void onChipClick(Detection deteccion);
    }

    private final List<Detection> equipos = new ArrayList<>();
    private final OnChipClickListener listener;

    public AdaptadorChipsEquipo(OnChipClickListener listener) {
        this.listener = listener;
    }

    /** Reemplaza la lista mostrada. Quien llama decide cuándo hace falta (evita parpadeo). */
    public void actualizar(List<Detection> nuevos) {
        equipos.clear();
        equipos.addAll(nuevos);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Celda onCreateViewHolder(@NonNull ViewGroup padre, int tipo) {
        View v = LayoutInflater.from(padre.getContext())
                .inflate(R.layout.item_chip_equipo, padre, false);
        return new Celda(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Celda celda, int posicion) {
        celda.mostrar(equipos.get(posicion), listener);
    }

    @Override
    public int getItemCount() {
        return equipos.size();
    }

    static class Celda extends RecyclerView.ViewHolder {

        private final ImageView foto;
        private final TextView nombre;

        Celda(@NonNull View v) {
            super(v);
            foto = v.findViewById(R.id.chipFoto);
            nombre = v.findViewById(R.id.chipNombre);
        }

        void mostrar(Detection deteccion, OnChipClickListener listener) {
            Context contexto = itemView.getContext();
            Bitmap bitmap = ImagenesEquipos.obtener(contexto, deteccion.label);
            foto.setImageBitmap(bitmap); // null limpia el ImageView, no falla

            nombre.setText(Equipos.nombreDe(contexto, deteccion.label));
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onChipClick(deteccion);
                }
            });
        }
    }
}
