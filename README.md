# BancoIAS

Backend reactivo para la creación, validación y consulta de transferencias entre cuentas. La solución está implementada con Spring Boot, WebFlux, Spring Data R2DBC, H2/PostgreSQL y RabbitMQ.

## Alcance implementado

- API REST reactiva para crear y consultar transferencias.
- Validación de datos de entrada mediante Jakarta Validation.
- Validación de monto, cuentas, estado de la cuenta origen y saldo disponible.
- Persistencia de todas las decisiones en la tabla `transfer`, tanto autorizadas como rechazadas.
- Idempotencia por `requestReference`: una referencia repetida devuelve la transferencia original.
- Débito atómico de la cuenta origen mediante una actualización condicionada por saldo y estado.
- Procesamiento concurrente sin permitir que el saldo disponible quede negativo.
- Publicación de la solicitud en RabbitMQ.
- Datos iniciales de demostración insertados automáticamente cuando no existen cuentas.
- Configuración alternativa para H2 local y PostgreSQL.
- Pruebas unitarias, pruebas de integración HTTP y prueba de concurrencia.

## Tecnologías

| Componente | Tecnología |
|---|---|
| Lenguaje | Java 21 |
| Framework | Spring Boot 4.1.1 |
| API | Spring WebFlux |
| Persistencia | Spring Data R2DBC |
| Base local | H2 en archivo |
| Base alternativa | PostgreSQL 16 |
| Mensajería | RabbitMQ 3 |
| Documentación API | Springdoc OpenAPI |
| Pruebas | JUnit 5, Mockito, Reactor Test, WebTestClient |
| Construcción | Maven Wrapper |

## Estructura principal

```text
src/main/java/com/bancoias/
├── api/                    Controladores, DTOs y manejo de errores
├── config/                 Configuración R2DBC, RabbitMQ y datos iniciales
├── domain/                 Entidades y enumeraciones del dominio
├── repositories/           Repositorios reactivos
└── service/                Reglas de negocio y publicación de mensajes

src/main/resources/
├── application.yaml        Configuración local con H2 y RabbitMQ
├── application-postgres.yaml
└── db/schema.sql            Esquema de base de datos

src/test/
├── .../TransferServiceTest.java
├── .../TransferApiIntegrationTest.java
└── .../TransferConcurrencyIntegrationTest.java
```

## Requisitos

- JDK 21 o superior.
- Docker Desktop si se desea ejecutar RabbitMQ o PostgreSQL mediante Docker Compose.
- No es necesario instalar Maven: el proyecto incluye `mvnw` y `mvnw.cmd`.

## Ejecución local con H2

La configuración predeterminada utiliza H2 en el archivo `data/bancoias.mv.db`.

1. Iniciar RabbitMQ:

   ```powershell
   docker compose up -d rabbitmq
   ```

2. Iniciar la aplicación:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

La aplicación queda disponible en `http://localhost:8080`.

RabbitMQ utiliza:

| Parámetro | Valor |
|---|---|
| Host | `localhost` |
| Puerto AMQP | `5672` |
| Usuario | `admin` |
| Contraseña | `admin` |
| Consola | `http://localhost:15672` |

Si IntelliJ IDEA tiene abierta la base H2 como conexión JDBC, debe desconectarse antes de iniciar la aplicación. H2 en modo archivo no permite que ambas conexiones abran simultáneamente el archivo.

## Ejecución con PostgreSQL

1. Iniciar PostgreSQL y RabbitMQ:

   ```powershell
   docker compose up -d postgres rabbitmq
   ```

2. Activar el perfil de PostgreSQL:

   ```powershell
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgres"
   ```

La configuración espera PostgreSQL en `localhost:5432`, con base `bancoias`, usuario `bancoias` y contraseña `bancoias`. El archivo `application-postgres.yaml` sobrescribe la URL y las credenciales R2DBC; RabbitMQ continúa utilizando la configuración común.

## Pruebas automatizadas

Ejecutar toda la suite:

```powershell
.\mvnw.cmd test
```

Ejecutar una clase específica:

```powershell
.\mvnw.cmd -Dtest=TransferServiceTest test
.\mvnw.cmd -Dtest=TransferApiIntegrationTest test
.\mvnw.cmd -Dtest=TransferConcurrencyIntegrationTest test
```

Las pruebas de integración utilizan H2 en memoria y no requieren una instancia externa de PostgreSQL. RabbitMQ se simula en las pruebas unitarias; para una prueba end-to-end de mensajería se debe levantar el servicio de Docker.

### Cobertura funcional de las pruebas

- Autorización de una transferencia válida.
- Rechazo por monto cero o negativo.
- Rechazo cuando origen y destino son la misma cuenta.
- Rechazo cuando alguna cuenta no existe.
- Rechazo cuando la cuenta origen está bloqueada.
- Rechazo por saldo insuficiente sin modificar el saldo.
- Idempotencia ante una referencia repetida.
- Consulta por referencia y listado ordenado por fecha descendente.
- Persistencia de transferencias autorizadas y rechazadas.
- Competencia entre 20 solicitudes simultáneas sin saldo negativo.
- Validación HTTP de campos obligatorios.

## API REST

### Crear una transferencia

```http
POST /api/transfers
Content-Type: application/json
```

Solicitud:

```json
{
  "requestReference": "REF-001",
  "sourceAccountId": "CTA-1001",
  "destinationAccountId": "CTA-2001",
  "amount": 600000
}
```

Respuesta autorizada (`201 Created`):

```json
{
  "success": true,
  "data": {
    "requestReference": "REF-001",
    "sourceAccountId": "CTA-1001",
    "destinationAccountId": "CTA-2001",
    "amount": 600000,
    "status": "AUTHORIZED",
    "reason": null,
    "processedAt": "2026-01-01T12:00:00Z"
  },
  "error": null
}
```

Una decisión de negocio rechazada también se persiste y responde `201 Created`, con `status: "REJECTED"` y una razón de rechazo.

Razones disponibles:

- `AMOUNT_NOT_POSITIVE`
- `SAME_ACCOUNT`
- `ACCOUNT_NOT_FOUND`
- `SOURCE_ACCOUNT_BLOCKED`
- `INSUFFICIENT_BALANCE`

### Consultar por referencia

```http
GET /api/transfers/{requestReference}
```

Devuelve `200 OK` si existe y `404 Not Found` si no existe.

### Listar transferencias recientes

```http
GET /api/transfers?limit=20
```

El parámetro `limit` se limita al rango de 1 a 100 y el resultado se ordena de la transferencia más reciente a la más antigua. La respuesta es un arreglo de `TransferResponse`.

### Códigos HTTP

| Código | Uso |
|---|---|
| `200 OK` | Consulta o listado exitoso |
| `201 Created` | Transferencia persistida, autorizada o rechazada por una regla de negocio |
| `400 Bad Request` | Error de validación de la solicitud |
| `404 Not Found` | Referencia de transferencia inexistente |
| `500 Internal Server Error` | Error no controlado |

Los errores controlados utilizan este formato:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "sourceAccountId: sourceAccountId es obligatorio"
  }
}
```

## Modelo de datos y decisiones técnicas

### Transferencias

La tabla `transfer` tiene una clave única sobre `request_reference`. Esta restricción, junto con el manejo de `DuplicateKeyException`, permite que una solicitud repetida sea idempotente y no vuelva a descontar el saldo.

Las transferencias actualmente tienen únicamente dos estados:

- `AUTHORIZED`
- `REJECTED`

Una transferencia rechazada por una regla de negocio se registra para conservar trazabilidad.

### Consistencia del saldo

El débito se ejecuta con una sentencia SQL condicionada:

- la cuenta debe estar `ACTIVE`;
- el saldo debe ser mayor o igual al monto;
- el saldo y la versión se actualizan en la misma operación.

La operación de autorización y el registro de la transferencia se ejecutan dentro de un `TransactionalOperator`. Esto evita que las solicitudes concurrentes autoricen más dinero del disponible.

### Reactividad

Los controladores y repositorios utilizan `Mono` y `Flux`. No se usan controladores MVC bloqueantes para las operaciones HTTP o de persistencia.

### Identificador temporal

La aplicación inyecta `java.time.Clock`, lo que permite usar un reloj fijo en las pruebas y un reloj UTC en ejecución normal.

## Decisiones y limitaciones conocidas

El alcance completado corresponde a la autorización o rechazo sincrónico de transferencias y su persistencia.

1. La cuenta destino no recibe actualmente un crédito; solo se descuenta la cuenta origen.
2. `PublisherRMQ` registra el error de publicación, pero no lo propaga al flujo HTTP. Por ello, un fallo de RabbitMQ no cambia por sí solo la respuesta de negocio.
3. La aplicación depende de que RabbitMQ esté disponible para iniciar correctamente debido al bean `RabbitTemplate`.
4. No se incluyen autenticación, autorización, límites por cliente ni observabilidad distribuida.
5. El esquema SQL se inicializa con `spring.sql.init.mode=always`. En un entorno productivo se recomienda usar una herramienta de migraciones como Flyway o Liquibase.

## Datos iniciales

Cuando la tabla de cuentas está vacía, `DataSeeder` crea:

| Cuenta | Cliente | Estado | Saldo |
|---|---|---|---:|
| `CTA-1001` | `USR-10` | `ACTIVE` | 1.000.000 |
| `CTA-1002` | `USR-10` | `BLOCKED` | 800.000 |
| `CTA-2001` | `USR-20` | `ACTIVE` | 2.000.000 |

Los datos son únicamente de demostración y no representan credenciales o información real.

## Uso de Inteligencia Artificial

Se utilizó una herramienta de Inteligencia Artificial como apoyo durante el desarrollo y la documentación del proyecto.

### Actividades en las que se utilizó

- Revisión del flujo de creación y persistencia de transferencias.
- Diagnóstico de errores de inicialización de Spring, R2DBC/H2 y RabbitMQ.
- Diseño y apoyo en la creación de las pruebas automatizadas unitarias, de integración HTTP y de concurrencia.
- Revisión de la configuración Maven y de los perfiles de base de datos.
- Configuración de PostgreSQL en Docker Compose, incluyendo la imagen, credenciales locales, puerto, volumen persistente y healthcheck.
- Análisis de endpoints, códigos HTTP, pruebas y limitaciones funcionales.
- Generación y organización de esta documentación.

### Resultados aprovechados

- Identificación de la dependencia de RabbitMQ y de la configuración de `ConnectionFactory`.
- Identificación del conflicto de puerto entre la aplicación y la configuración local de RabbitMQ.
- Identificación del bloqueo de la base H2 cuando se abre simultáneamente desde IntelliJ IDEA y la aplicación.
- Estructuración de pruebas para idempotencia, reglas de rechazo, persistencia, API y concurrencia.
- Configuración reproducible de PostgreSQL mediante Docker Compose para el perfil `postgres`.
- Documentación del comportamiento de idempotencia, débito condicional y pruebas de concurrencia.

### Validación aplicada

- Inspección directa del código fuente, configuración, esquema SQL y pruebas.
- Ejecución de Maven Wrapper para compilar el proyecto y ejecutar el ciclo de pruebas automatizadas disponible.
- Revisión de los escenarios cubiertos por las pruebas y de sus aserciones contra el comportamiento real del servicio.
- Revisión de la configuración de PostgreSQL en `docker-compose.yml` y su correspondencia con `application-postgres.yaml`.
- Ejecución controlada de la aplicación para reproducir y diagnosticar el error de bloqueo de H2.
- Comparación de la documentación con los endpoints y estados realmente implementados, evitando describir funcionalidades inexistentes como completadas.

### Resultados corregidos o descartados

- Se corrigió la configuración de la dependencia AMQP para que utilice la versión administrada por el parent de Spring Boot, evitando mezclar versiones.
- Se ajustó el puerto local de RabbitMQ de `5673` a `5672`, que es el puerto expuesto por Docker Compose.
- No se incorporaron cambios especulativos para agregar funcionalidades fuera del alcance evaluado, como autenticación o autorización.

### Protección de información sensible

- No se proporcionaron credenciales reales, tokens, claves privadas ni datos personales a la herramienta.
- Los valores de Docker Compose son credenciales locales de demostración (`admin/admin` y `bancoias/bancoias`).
- Se trabajó sobre archivos del repositorio y resultados técnicos necesarios para el diagnóstico.
- Los datos sembrados son ficticios y se identifican como datos de demostración.

### Completado

- Backend compilable.
- API de transferencias funcional.
- Persistencia de resultados autorizados y rechazados.
- Idempotencia por referencia.
- Validaciones de negocio y de entrada.
- Pruebas automatizadas de servicio, API y concurrencia.
- Ejecución local reproducible con H2 y Docker Compose.
- Configuración alternativa para PostgreSQL.
- Documentación de decisiones, uso de IA, limitaciones e historial.

