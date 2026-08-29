package com.example.rumiologia.asistente;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.rumiologia.R;

import java.util.List;

/**
 * Pinta la lista de mensajes del chat con Rumi.
 *
 * <p>Se usa un único layout para los tres tipos de mensaje y se cambian la
 * alineación, el fondo, el color del texto y el avatar según el origen. Con tres
 * variantes tan parecidas, tener tres layouts distintos sería más código para el
 * mismo resultado.
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
        private final LinearLayout fila;
        private final ImageView avatar;
        private final TextView texto;
        private final TextView fuentes;

        Celda(@NonNull View v) {
            super(v);
            contenedor = v.findViewById(R.id.burbujaContenedor);
            fila = v.findViewById(R.id.burbujaFila);
            avatar = v.findViewById(R.id.mensajeAvatar);
            texto = v.findViewById(R.id.mensajeTexto);
            fuentes = v.findViewById(R.id.mensajeFuentes);
        }

        void mostrar(Mensaje m) {
            boolean esUsuario = m.origen == Mensaje.Origen.USUARIO;
            boolean esError = m.origen == Mensaje.Origen.ERROR;

            int alineacion = esUsuario ? Gravity.END : Gravity.START;
            contenedor.setGravity(alineacion);
            fila.setGravity(alineacion);

            // El avatar de Rumi solo acompaña a sus respuestas: en los mensajes del
            // usuario sobra, y en los errores confundiría (no los dice Rumi).
            avatar.setVisibility(esUsuario || esError ? View.GONE : View.VISIBLE);

            int fondo;
            int colorTexto;
            if (esError) {
                fondo = R.drawable.burbuja_error;
                colorTexto = R.color.texto_burbuja_error;
            } else if (esUsuario) {
                fondo = R.drawable.burbuja_usuario;
                colorTexto = R.color.texto_burbuja_usuario;
            } else {
                fondo = R.drawable.burbuja_asistente;
                colorTexto = R.color.texto_burbuja_asistente;
            }
            texto.setBackgroundResource(fondo);
            texto.setTextColor(ContextCompat.getColor(itemView.getContext(), colorTexto));

            if (m.cargando) {
                texto.setText(R.string.chat_escribiendo);
                texto.setAlpha(0.6f);
            } else {
                texto.setText(m.texto);
                texto.setAlpha(1f);
            }

            // Las fuentes son lo que distingue una respuesta verificable de una
            // afirmación suelta: dicen de qué ficha salió el dato.
            if (m.fuentes != null && !m.fuentes.isEmpty()) {
                fuentes.setVisibility(View.VISIBLE);
                fuentes.setText(itemView.getContext()
                        .getString(R.string.chat_fuentes, nombresLegibles(m.fuentes)));
            } else {
                fuentes.setVisibility(View.GONE);
            }
        }

        /** Convierte los slugs de las fichas citadas en algo legible. */
        private String nombresLegibles(List<String> slugs) {
            StringBuilder sb = new StringBuilder();
            for (String slug : slugs) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(com.example.rumiologia.Equipos.nombreDe(itemView.getContext(), slug));
            }
            return sb.toString();
        }
    }
}
