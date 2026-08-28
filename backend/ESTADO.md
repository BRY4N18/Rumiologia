# Estado del backend: prueba de concepto temporal

> **Este backend es temporal.** Se construyó para comprobar que el enfoque RAG
> funciona con las fichas reales del laboratorio, no como la versión definitiva del
> servicio. La implementación final está pendiente de decidir y construir.

## Por qué existe

Antes de invertir tiempo en un servicio definitivo había que responder preguntas que
no se pueden contestar sobre el papel:

- ¿Las fichas técnicas contienen información suficiente para responder lo que un
  estudiante realmente pregunta?
- ¿El modelo se limita a las fichas o inventa cuando no encuentra el dato?
- ¿Distingue entre las dos estufas del laboratorio?
- ¿Cuánto tarda una respuesta?

Todas se respondieron. Ese era el objetivo.

## Qué quedó demostrado

Probado el 28/08/2026 con `google-genai 2.20.0`, `gemini-3.6-flash` y las 7 fichas
reales subidas a un File Search Store.

| Comprobación | Resultado |
|---|---|
| Responde con el procedimiento real | Sí — los 4 pasos de la ficha de la estufa ANKOM |
| Añade advertencias de seguridad sin pedirlas | Sí — sacó la del riesgo de explosión con acetona |
| Reconoce cuándo no sabe | Sí — "esa información no está en las fichas técnicas" |
| Desambigua equipos parecidos | Sí — pregunta cuál de las dos estufas |
| Mantiene el hilo de la conversación | Sí |
| Cita la ficha de origen | Sí |

**Conclusión: el enfoque funciona y las fichas son suficientes.** Eso es lo que había
que averiguar.

## Qué NO es

Este servicio **no está listo para uso real**, y no pretende estarlo:

| Aspecto | Estado actual | Por qué importa |
|---|---|---|
| Despliegue | Corre en la laptop de desarrollo | Si se apaga el PC, la app se queda sin asistente |
| Transporte | HTTP sin cifrar | Las preguntas viajan en claro; hay una excepción de seguridad puesta a mano en Android para permitirlo |
| Autenticación | Ninguna | Cualquiera en la red puede consumir la cuota de la API key |
| Límite de uso | Ninguno | Un bucle accidental agota los créditos |
| Registro de errores | Solo consola | No hay forma de saber qué falló ayer |
| Conversaciones | No se guardan | No se puede analizar qué preguntan los estudiantes |
| Actualizar las fichas | Crea un almacén nuevo cada vez | Deja almacenes huérfanos consumiendo cuota |
| Pruebas automatizadas | Ninguna | Cualquier cambio se verifica a mano |

La excepción de HTTP en claro está en
`app/src/main/res/xml/network_security_config.xml` y **debe eliminarse** cuando haya
HTTPS.

## Qué sí es reutilizable

Aunque el servicio se reescriba entero, esto no se tira:

- **La instrucción del sistema** (`app.py`). Es la pieza que impide que el modelo
  invente datos de laboratorio y la que obliga a mencionar las advertencias de
  seguridad. Está validada contra casos reales; conviene conservarla tal cual.
- **El contrato del endpoint** `/chat` — `{pregunta, equipo, historial}` →
  `{respuesta, fuentes}`. La app Android ya habla ese idioma. Si el servicio cambia
  por dentro pero respeta el contrato, la app no se toca.
- **El formato de las fichas** en Markdown con secciones `##`. Funciona igual de bien
  con RAG gestionado o propio.
- **El campo `equipo` como pista, no como filtro.** Permite que el usuario esté
  frente a una balanza y pregunte por otra cosa.

## Pendiente para la versión definitiva

Decisiones abiertas:

- [ ] Dónde se despliega el servicio
- [ ] Proveedor definitivo (Gemini u OpenAI)
- [ ] Si el RAG sigue siendo gestionado (File Search) o se implementa
- [ ] Cómo se autentica la app contra el servicio

Trabajo técnico:

- [ ] HTTPS y eliminar la excepción de tráfico en claro de Android
- [ ] Autenticación y límite de peticiones
- [ ] Actualizar fichas reutilizando el almacén en vez de crear uno nuevo
- [ ] Registro de errores persistente
- [ ] Pruebas automatizadas del endpoint
- [ ] Ajustar el presupuesto de razonamiento (`thinking_budget`): en las pruebas el
      modelo gastó 123 tokens de razonamiento para responder una sola palabra

## Si se abandona este backend

El File Search Store creado sigue existiendo en la cuenta de Google y ocupando
almacenamiento. Para eliminarlo:

```python
from google import genai
cliente = genai.Client(api_key="...")
cliente.file_search_stores.delete(name="fileSearchStores/...", config={"force": True})
```

El identificador del almacén está en `backend/.env`, que no se versiona.
