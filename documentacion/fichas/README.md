# Fichas técnicas de los equipos

Una ficha por cada clase que detecta el modelo. El identificador y el índice
coinciden con `ml/clases.json` y `app/src/main/assets/labels.txt`.

| # | Equipo | Ficha | Estado |
|---|---|---|---|
| 0 | ANKOM 200 Fiber Analyzer | [`ankom_200_fiber_analyzer.md`](ankom_200_fiber_analyzer.md) | Borrador |
| 1 | ANKOM DAISY Incubator | [`ankom_daisy_incubator.md`](ankom_daisy_incubator.md) | Borrador |
| 2 | ANKOM Estufa | [`ankom_estufa.md`](ankom_estufa.md) | Borrador |
| 3 | AQUASEARCHER AB33M1 (OHAUS) | [`aquasearcher_ab33m1.md`](aquasearcher_ab33m1.md) | Borrador |
| 4 | Contador de colonias | [`contador_de_colonias.md`](contador_de_colonias.md) | Borrador |
| 5 | MEMMERT | [`memmert.md`](memmert.md) | Sin identificar |
| 6 | Ohaus PR224 | [`ohaus_pr224.md`](ohaus_pr224.md) | Borrador |

## Cómo completarlas

Los campos `TODO` salen del manual del fabricante o de la placa de
características del equipo. Conviene priorizar **Qué es**, **Uso en el
laboratorio** y **Procedimiento básico**: son las secciones que el usuario
consultará al apuntar la cámara, y las que darán mejores respuestas cuando
estas fichas alimenten al asistente RAG.

El formato Markdown no es casual: se lee bien tal cual, se muestra fácil en
la app y se trocea de forma natural por secciones para indexarlo en el RAG.
