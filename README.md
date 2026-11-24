# gateway-admin

Gateway BFF (Backend For Frontend) do App Admin do AlgaShop. Ponto de entrada único
para gerenciar categorias, produtos, pedidos, clientes e usuários, validando o JWT
antes de encaminhar as requisições aos microsserviços.

## Rotas

| Recurso | Microsserviço | Endpoints |
|---------|---------------|-----------|
| Categorias | `product-catalog` | `GET/POST /api/v1/categories`, `GET/PUT /api/v1/categories/{categoryId}` |
| Produtos | `product-catalog` | `GET/POST /api/v1/products`, `GET/PUT /api/v1/products/{productId}`, `restock`, `withdraw` |
| Imagens de produto | `product-catalog` | `GET/POST /api/v1/products/{productId}/images`, `PUT .../{imageId}/primary`, `DELETE .../{imageId}` |
| Upload-requests | `product-catalog` | `POST /api/v1/upload-requests` |
| Clientes | `ordering` | `GET /api/v1/customers`, `GET /api/v1/customers/{customerId}` |
| Pedidos | `ordering` | `GET /api/v1/orders`, `GET /api/v1/orders/{orderId}` |
| Fatura do pedido | `billing` | `GET /api/v1/orders/{orderId}/invoice` |
| Usuários | `authorization-server` | `GET/POST /api/v1/users`, `GET/PUT /api/v1/users/{userId}` |

## Resiliência

- **Rate limit** por usuário (`sub` do JWT) com estado no Redis (database `4`).
- **Cache local de respostas** para requisições `GET` (`1m`, `10MB`, Caffeine).
- **Timeout** de conexão (`1s`) e de resposta (`5s`) no cliente HTTP do Gateway.
- **Retry** para `GET/PUT` em `502/500/504` e **Circuit Breaker** nas rotas do `ordering`.

## Segurança

Resource Server OAuth2 validando o Bearer JWT emitido por `http://auth.algashop.local:8081`.
O token original é repassado aos microsserviços, que realizam sua própria validação.

## Execução local

Mapeie o hostname (Linux/macOS):

```bash
echo "127.0.0.1 admin-api.algashop.local" | sudo tee -a /etc/hosts
```

Suba a infraestrutura (Redis, service registry, authorization server) e execute:

```bash
./gradlew bootRun
```

O Gateway estará disponível em `http://admin-api.algashop.local:9998`.

## Docker

```bash
./gradlew dockerBuild
docker compose -f ../docker-compose.services.yml up algashop-gateway-admin
```

## Testes e cobertura

```bash
./gradlew test
```

O relatório JaCoCo é gerado em `build/reports/jacoco/test/html`.
