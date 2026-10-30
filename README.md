# Sniper AR

Juego para Android que convierte el celular en la **mira de un rifle de francotirador** con realidad
aumentada hecha a mano (sin ARCore). Se monta el celular sobre una réplica de rifle, el tirador queda
a la distancia real de una mira (alivio de ojo) y apunta moviendo el cuerpo: los sensores siguen el
movimiento y los blancos quedan fijos en el mundo.

## Descarga

Cada cambio en `main` compila el APK con GitHub Actions y lo publica en
[Releases](../../releases). Desde el celular: abrir la última release, bajar `SniperAR-N.apk` e
instalarlo (hay que permitir "instalar apps de orígenes desconocidos").

## Cómo se juega

| Opción | Qué hace |
|---|---|
| **Blancos virtuales 3D** | Personas, ciervos, jabalíes y guanacos low-poly (o placas de acero en *Práctica*) que caminan por el terreno en coordenadas del mundo, a 50–1000 m. Fondo: cámara (AR) o escenario sintético. |
| **Blancos reales** | La cámara detecta personas o animales con MediaPipe. Aparece sólo la etiqueta (sin recuadros ni porcentajes). La distancia se estima por el tamaño angular, como un telémetro de retícula. |
| **Tirador / Cazador** | Humanos o animales. En animales cuenta la zona vital (corazón/pulmones). |
| **Vista** | Sólo el círculo de la mira con el resto oscurecido, o pantalla completa. |
| **Aumentos y distancia ojo–pantalla** | El zoom de la cámara se calcula para que la pantalla, vista a esa distancia, se comporte como una mira real de N aumentos. |

**Gatillo:** botón FUEGO, teclas de volumen, o un control Bluetooth (los disparadores de selfie
mandan "volumen +"). Un gamepad también sirve: cruceta = torretas, A/R1 = disparo, X = recargar.
Con **Ocultar** desaparecen los botones para usarlo montado.

**Torretas:** MRAD (0,1) o MOA (¼). La retícula es de primer plano focal: las marcas miden
miliradianes reales a cualquier aumento, con árbol de compensación de viento. Con la asistencia
activada, la computadora de tiro muestra la solución y un rombo amarillo indica dónde sostener;
**AUTO** ajusta las torretas. En modo realista no hay ayudas: hay que leer el viento (flecha y
reloj) y medir con la retícula.

**Nivel de burbuja:** inclinar el rifle (cant) desvía el tiro, igual que en la realidad.

## Física

- Punto material con arrastre **G7** (tabla estándar), densidad del aire por temperatura y altitud,
  integración Runge-Kutta 4.
- Viento con ráfagas, **Coriolis** (latitud de Buenos Aires por defecto) y **deriva por giro**
  (factor de estabilidad de Miller + fórmula de Litz).
- Ángulo de cero resuelto por bisección para la distancia elegida; cerrojo, cargador de 5 y recarga.
- `tools/verify_ballistics.py` reimplementa el modelo en NumPy y lo compara con tablas publicadas
  (Hornady 6.5 CM 140 ELD-M: 3,94 mrad a 600 yd y 8,87 mrad a 1000 yd, contra 4,0 y 8,8 publicados).
  `tools/run_jvm_tests.sh` compila el núcleo Kotlin en la JVM y verifica que coincide con Python.

| Cartucho | V0 | BC G7 |
|---|---|---|
| .308 Win 175 gr SMK | 792 m/s | 0,243 |
| 6.5 Creedmoor 140 gr ELD-M | 823 m/s | 0,326 |
| .338 Lapua 300 gr SMK | 838 m/s | 0,383 |
| .30-06 180 gr | 820 m/s | 0,226 |
| .223 Rem 77 gr | 838 m/s | 0,190 |
| .50 BMG 750 gr A-MAX | 860 m/s | 0,520 |

## Tecnología

- **Kotlin**, app nativa Android 8.0+ (minSdk 26), interfaz armada en código, sin XML de layouts.
- **CameraX** para la vista previa de la cámara trasera, el zoom y el análisis de imágenes.
- **Camera2** (interop) para leer focal, tamaño de sensor y área activa y calcular el FOV real.
- **Realidad aumentada propia:** filtro complementario en cuaterniones (giroscopio + acelerómetro +
  brújula) y proyección pinhole mundo→pantalla (`core/CameraModel.kt`).
- **MediaPipe Tasks Vision** con **EfficientDet-Lite0** (COCO). Se usan 11 clases: persona, ave,
  gato, perro, caballo, oveja, vaca, elefante, oso, cebra y jirafa. El modelo se descarga al compilar.
- **Renderizador 3D propio** sobre Canvas: cajas low-poly con medidas reales, sombreado por luz solar,
  bruma por distancia y orden de pintor.
- **RenderEffect** (Android 12+): desenfoque al disparar y visor térmico.
- **AudioTrack** con sintetizador propio: disparo con ecos, cerrojo, clics de torreta, impactos
  retrasados por la velocidad del sonido, campanazo del acero y viento ambiente.
- **Python (NumPy + Pillow):** `tools/generate_assets.py` genera polvo, chispas, humo, la
  ilustración del menú y el ícono.
- **Gradle** + **GitHub Actions** → APK firmado en Releases.

## Estructura

```
app/src/main/java/com/guileman/snipervr/
  core/     Vec3/Quat, balística, cámara, fusión de sensores (Kotlin puro, probado en JVM)
  game/     modelos low-poly, blancos, disparos, modo real (Kotlin puro)
  android/  CameraX/Camera2, sensores, detector MediaPipe
  render/   renderizador 3D sobre Canvas
  audio/    sintetizador
  ui/       menú, pantalla de juego, HUD y retícula
tools/      verificación balística, arnés JVM, generador de gráficos
```

## Compilar localmente

```
./gradlew assembleRelease
```

Requiere JDK 17 y Android SDK 35. El APK se firma con `keystore/snipervr.jks` (clave de ejemplo,
incluida para que las actualizaciones instalen encima). Para publicar en Play Store usá tu propia
clave: cargala en Secrets como `KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` y
`SIGNING_KEY_PASSWORD`, y el workflow la usa automáticamente.

## Uso responsable

En el modo de blancos reales apuntá a personas sólo si saben que están jugando. Es un juego: no
usar en la vía pública ni de forma que pueda asustar a alguien.
