# Radio Recuerdos Retro — app Android

Reproductor de la radio en vivo (clásicos de los 80 y 90) con estilo retro.

- Transmisión: `https://sonic.portalfoxmix.cl/8226/stream`
- Sigue sonando minimizada o con pantalla apagada, con controles en la notificación y en la pantalla de bloqueo (reproducir/pausa y detener), auriculares y Bluetooth.
- Muestra el tema y el artista que suenan y su carátula. La carátula se toma del panel de la radio
  (`/cp/get_info.php?p=8226`); si el panel no trae una, se busca automáticamente por artista y tema.
- Temporizador de apagado, compartir tema y control de volumen.

## Archivos importantes

- `app/src/main/assets/index.html` — toda la interfaz (pantalla de carga y reproductor).
- `app/src/main/java/cl/radiorecuerdos/retro/RadioService.kt` — audio en segundo plano, notificación y carátulas
  (ahí están las URL del stream y del panel).
- `app/firma.jks` — firma fija.

## Cómo actualizar

1. Modificar los archivos y subir el cambio a `main`.
2. GitHub compila solo y publica el APK en **Releases** (`radio-recuerdos-retro.apk`), con versión 1.N automática.
   Una copia queda también en la rama `apk-build`.
3. Instalar el APK nuevo encima del anterior (no hay que desinstalar).

La clave de la firma está guardada como secreto del repositorio (`FIRMA_CLAVE`, en Settings → Secrets and variables → Actions), no en el código.

**No borrar `app/firma.jks` ni el secreto `FIRMA_CLAVE`**: es la firma fija. Gracias a ella las actualizaciones se instalan encima de la versión anterior.
