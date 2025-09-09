# CamStamp

CamStamp es una aplicación de Android diseñada para capturar fotos y estamparlas automáticamente con información relevante como texto personalizado, la fecha y hora de la captura, y las coordenadas geográficas (latitud y longitud) del lugar donde se tomó la foto.

## Características Implementadas

*   **Vista Previa de Cámara**: Muestra la vista en vivo de la cámara.
*   **Captura de Fotos**: Permite tomar fotografías.
*   **Texto Personalizado**: El usuario puede ingresar un texto que se asociará con la foto (preparado para futuro estampado).
*   **Obtención de Ubicación**: Captura las coordenadas de latitud y longitud al momento de tomar la foto (preparado para futuro estampado).
*   **Zoom de Cámara**: Permite ajustar el nivel de zoom de la cámara mediante un control deslizante.
*   **Cambio de Cámara**: Permite alternar entre la cámara frontal y trasera del dispositivo.
*   **Interfaz de Usuario Moderna**: Construida con Jetpack Compose.
*   **Gestión de Permisos**: Solicita los permisos necesarios para la cámara y la ubicación.

## Tecnologías y Bibliotecas Utilizadas

*   **Kotlin**: Como lenguaje principal de programación.
*   **Jetpack Compose**: Para la construcción de la interfaz de usuario.
*   **CameraX**: Para la funcionalidad de la cámara (vista previa, captura, zoom, cambio de cámara).
*   **Servicios de Ubicación de Google (FusedLocationProviderClient)**: Para obtener la ubicación geográfica.
*   **Catálogo de Versiones de Gradle (libs.versions.toml)**: Para la gestión de dependencias.
*   **Corrutinas de Kotlin**: Para operaciones asíncronas.
*   **LiveData**: Para observar estados de la cámara.

## Configuración y Compilación

1.  **Clonar el Repositorio**:
    ```bash
    git clone <URL_DEL_REPOSITORIO> # Reemplaza con la URL de tu repositorio si lo subes a Git
    cd CamStamp
    ```
2.  **Abrir en Android Studio**:
    *   Abre Android Studio.
    *   Selecciona "Open an existing Android Studio project".
    *   Navega hasta el directorio donde clonaste el proyecto y selecciónalo.
3.  **Sincronizar Gradle**:
    *   Espera a que Android Studio indexe los archivos y sincronice el proyecto con los archivos de Gradle. Si no lo hace automáticamente, puedes forzar la sincronización desde `File > Sync Project with Gradle Files` o haciendo clic en el icono correspondiente.
4.  **Ejecutar la Aplicación**:
    *   Selecciona un dispositivo o emulador.
    *   Haz clic en el botón "Run 'app'" (el triángulo verde).

## Permisos Requeridos

La aplicación solicita los siguientes permisos en tiempo de ejecución:

*   `android.permission.CAMERA`: Para acceder a la cámara del dispositivo.
*   `android.permission.ACCESS_FINE_LOCATION`: Para acceder a la ubicación precisa del dispositivo.
*   `android.permission.ACCESS_COARSE_LOCATION`: Para acceder a la ubicación aproximada del dispositivo.

## Futuras Mejoras (Sugerencias)

*   Implementación completa del estampado de la información (texto, fecha/hora, ubicación) en la imagen guardada.
*   Visualización de la última foto tomada en la interfaz.
*   Creación de una galería interna para ver las fotos tomadas.
*   Mejoras en la interfaz de usuario y la experiencia de usuario.
