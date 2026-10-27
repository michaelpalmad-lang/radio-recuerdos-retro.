# Panel "Enviar aviso" — Radio Recuerdos Retro

Página para que el equipo de la radio envíe avisos push a todos los oyentes de la app, sin entrar a Firebase.

- Se publica en Netlify desde este mismo repositorio (Base directory: `panel-avisos`).
- Envía al tema `todos`, al que la app se suscribe sola. No hace falta actualizar la app.
- Solo envío inmediato (sin programar).

## Variables de entorno en Netlify

| Variable | Valor |
|---|---|
| `FIREBASE_SERVICE_ACCOUNT` | El contenido completo del archivo JSON de la cuenta de servicio de Firebase (Configuración del proyecto → Cuentas de servicio → Generar nueva clave privada). |
| `PANEL_CLAVE` | La contraseña que usará el equipo para entrar al panel. |

La clave de Firebase **nunca** se sube al repositorio: solo vive en Netlify.

## Imágenes

Las imágenes fijas están en `public/img/aviso-*.jpg` (1024×512). Para cambiar una, reemplaza el archivo con el mismo nombre.
