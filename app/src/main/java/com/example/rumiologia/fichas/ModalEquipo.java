package com.example.rumiologia.fichas;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.FragmentManager;

import com.example.rumiologia.EstadoRed;
import com.example.rumiologia.Equipos;
import com.example.rumiologia.R;
import com.example.rumiologia.asistente.ChatActivity;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.io.File;
import java.util.Locale;

/**
 * Hoja inferior que aparece al tocar un equipo detectado.
 *
 * <p>Ofrece los dos caminos del flujo:
 *
 * <ul>
 *   <li><b>Ficha técnica</b> — abre el PDF que viaja dentro del APK. Funciona
 *       siempre, también sin conexión.</li>
 *   <li><b>Chat con Rumi</b> — necesita internet, porque consulta a Gemini.</li>
 * </ul>
 *
 * <p>Que la ficha funcione sin conexión no es casualidad: es la razón de llevar los
 * PDF dentro del APK en lugar de descargarlos. En un laboratorio la señal puede ser
 * mala, y el procedimiento de operación es justo lo que alguien necesita consultar
 * con el equipo delante.
 */
public class ModalEquipo extends BottomSheetDialogFragment {

    private static final String ETIQUETA = "ModalEquipo";
    private static final String ARG_SLUG = "slug";
    private static final String ARG_CONFIANZA = "confianza";

    /** Muestra la hoja para un equipo detectado. */
    public static void mostrar(@NonNull FragmentManager gestor, String slug, float confianza) {
        ModalEquipo modal = new ModalEquipo();
        Bundle argumentos = new Bundle();
        argumentos.putString(ARG_SLUG, slug);
        argumentos.putFloat(ARG_CONFIANZA, confianza);
        modal.setArguments(argumentos);
        modal.show(gestor, ETIQUETA);
    }

    private String slug;
    private float confianza;

    /**
     * Tema propio del modal.
     *
     * <p>MainActivity arranca con el tema de la pantalla de presentacion, que no
     * desciende de Material. Los componentes de Material lo comprueban al inflarse
     * y lanzan una excepcion que cierra la app. Fijando aqui el tema, la hoja deja
     * de depender del de la Activity.
     */
    @Override
    public int getTheme() {
        return R.style.Theme_Rumiologia_BottomSheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflador, @Nullable ViewGroup contenedor,
                             @Nullable Bundle savedInstanceState) {
        return inflador.inflate(R.layout.modal_equipo, contenedor, false);
    }

    @Override
    public void onViewCreated(@NonNull View vista, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(vista, savedInstanceState);

        Bundle argumentos = requireArguments();
        slug = argumentos.getString(ARG_SLUG, "");
        confianza = argumentos.getFloat(ARG_CONFIANZA, 0f);

        Context contexto = requireContext();
        String nombre = Equipos.nombreDe(contexto, slug);

        ((TextView) vista.findViewById(R.id.modalNombreEquipo)).setText(nombre);
        ((TextView) vista.findViewById(R.id.modalChipConfianza)).setText(
                getString(R.string.modal_confianza, Math.round(confianza * 100)));

        configurarFicha(vista, contexto);
        configurarChat(vista, contexto, nombre);
    }

    private void configurarFicha(View vista, Context contexto) {
        LinearLayout boton = vista.findViewById(R.id.modalBotonFicha);
        RepositorioFichas repositorio = new RepositorioFichas(contexto);

        if (!repositorio.existe(slug)) {
            // Un equipo sin ficha no debería existir, pero si pasa es mejor
            // decirlo que ofrecer un botón que no hace nada.
            boton.setBackgroundResource(R.drawable.tarjeta_gris);
            boton.setEnabled(false);
            ((TextView) vista.findViewById(R.id.modalFichaDetalle))
                    .setText(R.string.modal_ficha_no_disponible);
            return;
        }

        boton.setOnClickListener(v -> abrirFicha(contexto, repositorio));
    }

    private void abrirFicha(Context contexto, RepositorioFichas repositorio) {
        File archivo = repositorio.prepararParaAbrir(slug);
        if (archivo == null) {
            Toast.makeText(contexto, R.string.modal_ficha_error, Toast.LENGTH_LONG).show();
            return;
        }

        // Un archivo de nuestra caché no es accesible para otras apps. El
        // FileProvider genera una URI temporal y el flag le concede permiso de
        // lectura solo a la app que abra el PDF.
        Uri uri = FileProvider.getUriForFile(
                contexto, contexto.getPackageName() + ".fileprovider", archivo);

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/pdf");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(intent);
            dismiss();
        } catch (ActivityNotFoundException e) {
            Toast.makeText(contexto, R.string.modal_sin_lector_pdf, Toast.LENGTH_LONG).show();
        }
    }

    private void configurarChat(View vista, Context contexto, String nombre) {
        LinearLayout boton = vista.findViewById(R.id.modalBotonChat);
        TextView detalle = vista.findViewById(R.id.modalChatDetalle);
        ImageView icono = vista.findViewById(R.id.modalIconoRumi);

        if (!EstadoRed.hayInternet(contexto)) {
            // Deshabilitado con explicación visible: un botón que no responde sin
            // decir por qué se lee como un fallo de la app.
            boton.setBackgroundResource(R.drawable.tarjeta_gris);
            boton.setEnabled(false);
            icono.setAlpha(0.4f);
            ((TextView) vista.findViewById(R.id.modalChatTitulo))
                    .setTextColor(getResources().getColor(R.color.texto_secundario, null));
            detalle.setText(R.string.modal_chat_sin_internet);
            return;
        }

        boton.setOnClickListener(v -> {
            startActivity(ChatActivity.intentPara(contexto, slug, nombre));
            dismiss();
        });
    }

    /** Nombre legible del equipo, por si hiciera falta fuera de la vista. */
    public String nombreEquipo() {
        return Equipos.nombreDe(requireContext(), slug);
    }

    @Override
    public String toString() {
        return String.format(Locale.getDefault(), "ModalEquipo(%s, %.0f%%)",
                slug, confianza * 100);
    }
}
