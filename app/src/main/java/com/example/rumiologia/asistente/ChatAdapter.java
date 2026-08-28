package com.example.rumiologia.asistente;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.rumiologia.R;

import java.util.List;

/**
 * Pinta la lista de mensajes.
 *
 * <p>Se usa un único layout para los tres tipos de mensaje y se cambia la
 * alineación y el color según el origen. Con tres variantes tan parecidas, tener
 * tres layouts distintos sería más código para el mismo resultado.
 */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.Celda> {

    private final List<Mensaje> mensajes;

    public ChatAdapter(List<Mensaje> mensajes) {
        this.mensajes = mensajes;
    }

    @NonNull
    @Override
    public Celda onCreateViewHolder(@NonNull ViewGroup padre, int tipo) {
        View v = LayoutInflater.from(padre.getContext())
                .inflate(R.layout.item_mensaje, padre, false);
        return new Celda(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Celda celda, int posicion) {
        celda.mostrar(mensajes.get(posicion));
    }

    @Override
    public int getItemCount() {
        return mensajes.size();
    }

    static class Celda extends RecyclerView.ViewHolder {

        private final LinearLayout contenedor;
        private final TextView texto;
        private final TextView fuentes;

        Celda(@NonNull View v) {
            super(v);
            contenedor = v.findViewById(R.id.burbujaContenedor);
            texto = v.findViewById(R.id.mensajeTexto);
            fuentes = v.findViewById(R.id.mensajeFuentes);
        }

        void mostrar(Mensaje m) {
            boolean esUsuario = m.origen == Mensaje.Origen.USUARIO;

            contenedor.setGravity(esUsuario ? android.view.Gravity.END : android.view.Gravity.START);

            int fondo;
            if (m.origen == Mensaje.Origen.ERROR) {
                fondo = R.drawable.burbuja_error;
            } else if (esUsuario) {
                fondo = R.drawable.burbuja_usuario;
            } else {
                fondo = R.drawable.burbuja_asistente;
            }
            texto.setBackgroundResource(fondo);

            if (m.cargando) {
                texto.setText(R.string.chat_escribiendo);
                texto.setAlpha(0.6f);
            } else {
                texto.setText(m.texto);
                texto.setAlpha(1f);
            }

            // Las fuentes son lo que distingue una respuesta verificable de una
            // afirmación suelta: dicen de qué ficha salió cada dato.
            if (m.fuentes != null && !m.fuentes.isEmpty()) {
                fuentes.setVisibility(View.VISIBLE);
                fuentes.setText(itemView.getContext()
                        .getString(R.string.chat_fuentes, String.join(", ", m.fuentes)));
            } else {
                fuentes.setVisibility(View.GONE);
            }
        }
    }
}
