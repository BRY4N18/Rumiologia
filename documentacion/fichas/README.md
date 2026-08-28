# Fichas técnicas de los equipos

Una ficha por cada clase que detecta el modelo. El identificador y el índice
coinciden con `ml/clases.json` y `app/src/main/assets/labels.txt`.

Contenido tomado de los instructivos del laboratorio elaborados por la
Ing. Nathaly Mera Macías (Técnico de Laboratorio, Laboratorio de Biología y
Microbiología). Los originales en Word están en `MOVIL APP EXAM/`.

| # | Equipo | Ficha |
|---|---|---|
| 0 | Analizador de Fibra ANKOM 200 | [`ankom_200_fiber_analyzer.md`](ankom_200_fiber_analyzer.md) |
| 1 | Incubadora ANKOM DAISY | [`ankom_daisy_incubator.md`](ankom_daisy_incubator.md) |
| 2 | Estufa de Secado ANKOM | [`ankom_estufa.md`](ankom_estufa.md) |
| 3 | Medidor Multiparámetro AQUASEARCHER AB33M1 (OHAUS) | [`aquasearcher_ab33m1.md`](aquasearcher_ab33m1.md) |
| 4 | Contador de Colonias | [`contador_de_colonias.md`](contador_de_colonias.md) |
| 5 | Estufa Universal MEMMERT | [`memmert.md`](memmert.md) |
| 6 | Balanza Analítica OHAUS PR224 | [`ohaus_pr224.md`](ohaus_pr224.md) |

## Estructura de cada ficha

Todas siguen las mismas secciones, y no es casual: el asistente RAG trocea los
documentos por encabezados `##`, de modo que cada sección es una unidad de
búsqueda independiente. Una pregunta como *«¿cómo enciendo la estufa?»*
recupera **Procedimiento básico de operación**, y *«¿qué cuidados tiene?»*
recupera **Precauciones y seguridad**.

| Sección | Responde a |
|---|---|
| Qué es y para qué sirve | ¿Qué es este equipo? |
| Usos principales | ¿Para qué se usa en el laboratorio? |
| Procedimiento básico de operación | ¿Cómo lo enciendo y lo opero? |
| Precauciones y seguridad | ¿Qué riesgos tiene? ¿Qué no debo hacer? |
| Datos y valores de referencia | Temperaturas, capacidades, tiempos |
