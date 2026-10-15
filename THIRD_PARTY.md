# Dependencias de terceros

Todas tienen licencia libre compatible con el reglamento del hackathon.

| Librería | Versión | Licencia | Uso |
|---|---|---|---|
| Spring Boot (webmvc, security, oauth2-resource-server, data-jpa, flyway, validation, actuator) | 4.1.1 | Apache 2.0 | Framework |
| Hibernate ORM | 7.4.x (gestionada por Boot) | Apache 2.0 | JPA |
| Kotlin stdlib / reflect | 2.3.21 | Apache 2.0 | Lenguaje |
| Jackson 3 + jackson-module-kotlin | 3.x (gestionada por Boot) | Apache 2.0 | JSON |
| Nimbus JOSE + JWT | gestionada por Spring Security | Apache 2.0 | Firma y verificación de JWT |
| springdoc-openapi-starter-webmvc-ui | 3.1.1 | Apache 2.0 | OpenAPI y Swagger UI |
| Flyway (core + database-postgresql) | 12.x (gestionada por Boot) | Apache 2.0 | Migraciones |
| PostgreSQL JDBC | 42.7.x (gestionada por Boot) | BSD-2-Clause | Driver |
| HikariCP | gestionada por Boot | Apache 2.0 | Pool de conexiones |
| Apache PDFBox | 3.0.8 | Apache 2.0 | Hoja PDF de QR y extracción de texto de órdenes |
| ZXing (core, javase) | 3.5.4 | Apache 2.0 | Generación de QR (y lectura en pruebas) |
| TwelveMonkeys ImageIO WebP | 3.15.3 | BSD-3-Clause | Lectura de fotos WEBP |
| Anthropic Java SDK (`com.anthropic:anthropic-java`) | 2.71.0 | MIT | Extracción de materiales con IA (opcional) |
| OkHttp (dependencia del SDK de Anthropic) | transitiva | Apache 2.0 | Cliente HTTP |
| **Pruebas** | | | |
| JUnit 5 | gestionada por Boot | EPL 2.0 | Pruebas |
| MockK | 1.14.11 | Apache 2.0 | Mocks |
| springmockk | 5.0.1 | Apache 2.0 | MockK en el contexto de Spring |
| Testcontainers (junit-jupiter, postgresql) | 2.0.x (gestionada por Boot) | MIT | Postgres real en las pruebas |

Imágenes Docker: `postgres:16-alpine` (PostgreSQL License) y `eclipse-temurin:21` (GPLv2 con Classpath Exception; solo es el runtime, no se redistribuye código modificado).
