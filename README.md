# Sistema de Votação — Microsserviços com Quarkus

Sistema de votação construído com arquitetura de microsserviços usando **Quarkus 3.8**, **Java 17** e orquestrado via **Docker Compose**. A aplicação é composta por três serviços independentes que se comunicam entre si para gerenciar eleições, registrar votos e exibir resultados em tempo real.

---

## Arquitetura

```
                        ┌─────────────────────────────────────────┐
                        │         Traefik (Reverse Proxy)          │
                        │              porta 81 → 80               │
                        └──────┬──────────────┬───────────┬────────┘
                               │              │           │
              /api (exceto /api/volting)  /api/volting    /
                               │              │           │
                    ┌──────────▼──┐   ┌───────▼───┐  ┌───▼──────────┐
                    │ election-   │   │ volting-  │  │  result-app  │
                    │ management  │   │   app     │  │              │
                    └──────┬──────┘   └───────────┘  └──────────────┘
                           │               │  ▲               │
                    SQL + Redis         Redis (vote)   REST Client
                           │               │           (pooling 10s)
                    ┌──────▼──────┐   ┌────▼──────┐          │
                    │  MariaDB    │   │   Redis   │◄─────────┘
                    └─────────────┘   └───────────┘
```

### Serviços da aplicação

| Serviço               | Responsabilidade                                                                 |
|-----------------------|----------------------------------------------------------------------------------|
| `election-management` | Gerencia candidatos e eleições; persiste no MariaDB e sincroniza votos via Redis |
| `volting-app`         | Recebe votos dos usuários; lê/escreve contagem de votos diretamente no Redis     |
| `result-app`          | Exibe os resultados em tempo real via Server-Sent Events (SSE), consultando o `election-management` a cada 10 segundos |

### Infraestrutura de suporte

| Serviço       | Finalidade                                              | Porta/Acesso                          |
|---------------|---------------------------------------------------------|---------------------------------------|
| **Traefik**   | Reverse proxy e roteamento de tráfego                   | `81` (HTTP), `8080` (dashboard)       |
| **MariaDB**   | Banco relacional para candidatos e eleições             | interno                               |
| **Redis**     | Cache de eleições e contagem de votos em tempo real     | interno                               |
| **Graylog**   | Centralização de logs (GELF UDP)                        | `logging.private.dio.localhost`       |
| **Jaeger**    | Rastreamento distribuído (OpenTelemetry)                | `telemetry.private.dio.localhost`     |
| **OpenSearch**| Backend de busca para o Graylog                        | interno                               |
| **MongoDB**   | Persistência de metadados do Graylog                    | interno                               |

---

## Como funciona

### Fluxo principal

1. **Cadastro de candidatos** — via `election-management`, candidatos são criados e armazenados no MariaDB.
2. **Submissão da eleição** — ao chamar `POST /api/election`, uma eleição é criada com todos os candidatos cadastrados e persistida tanto no MariaDB quanto no Redis.
3. **Sincronização automática** — um scheduler no `election-management` roda a cada 5 segundos, sincronizando os votos acumulados no Redis de volta para o MariaDB.
4. **Votação** — o `volting-app` escuta o canal `elections` do Redis (pub/sub). Ao receber um voto via `POST /api/volting/elections/{electionId}/candidates/{candidateId}`, incrementa o contador no Redis.
5. **Resultado em tempo real** — o `result-app` consulta o `election-management` a cada 10 segundos e transmite os resultados via SSE (Server-Sent Events) para o cliente.

### Estratégia de cache no `volting-app`

- No startup, o `Cache` carrega todas as eleições do Redis para memória.
- O `Subscribe` assina o canal `elections` no Redis pub/sub para receber novas eleições em tempo real.

---

## Pré-requisitos

- [Docker](https://docs.docker.com/get-docker/) e [Docker Compose](https://docs.docker.com/compose/)
- [Java 17+](https://adoptium.net/) (apenas para desenvolvimento local)
- [Maven Wrapper](https://maven.apache.org/wrapper/) (incluso em cada serviço via `./mvnw`)

---

## Como executar

### 1. Subir toda a stack com Docker Compose

```bash
# Na raiz do projeto
docker compose up --build
```

Isso irá compilar e iniciar todos os serviços. O Traefik ficará disponível em `http://localhost:81`.

> **Atenção:** adicione as seguintes entradas ao seu `/etc/hosts` para acessar pelos hostnames configurados no Traefik:
> ```
> 127.0.0.1  vote.dio.localhost
> 127.0.0.1  logging.private.dio.localhost
> 127.0.0.1  telemetry.private.dio.localhost
> ```

### 2. Verificar saúde dos serviços

```bash
curl http://vote.dio.localhost/q/health
```

---

## Endpoints da API

Todos os endpoints abaixo são acessados via `http://vote.dio.localhost` (porta 81).

### `election-management` → prefixo `/api`

| Método | Endpoint                | Descrição                           |
|--------|-------------------------|-------------------------------------|
| `POST` | `/api/candidates`       | Cria um novo candidato              |
| `PUT`  | `/api/candidates/{id}`  | Atualiza dados de um candidato      |
| `GET`  | `/api/candidates`       | Lista todos os candidatos           |
| `POST` | `/api/election`         | Submete/inicia uma nova eleição     |
| `GET`  | `/api/election`         | Lista todas as eleições             |

#### Exemplo — criar candidato

```bash
curl -X POST http://vote.dio.localhost/api/candidates \
  -H "Content-Type: application/json" \
  -d '{"name": "João Silva"}'
```

#### Exemplo — iniciar eleição

```bash
curl -X POST http://vote.dio.localhost/api/election
```

---

### `volting-app` → prefixo `/api/volting`

| Método | Endpoint                                                      | Descrição                        |
|--------|---------------------------------------------------------------|----------------------------------|
| `GET`  | `/api/volting`                                                | Lista eleições disponíveis       |
| `POST` | `/api/volting/elections/{electionId}/candidates/{candidateId}`| Registra um voto                 |

#### Exemplo — votar

```bash
curl -X POST http://vote.dio.localhost/api/volting/elections/<electionId>/candidates/<candidateId>
```

---

### `result-app` → raiz `/`

| Método | Endpoint | Descrição                                         |
|--------|----------|---------------------------------------------------|
| `GET`  | `/`      | Stream SSE com resultados atualizados a cada 10s  |

#### Exemplo — acompanhar resultados

```bash
curl -N http://vote.dio.localhost/
```

---

## Documentação interativa (Swagger UI)

O `election-management` expõe o Swagger UI via SmallRye OpenAPI:

```
http://vote.dio.localhost/api/q/swagger-ui
```

---

## Desenvolvimento local (sem Docker)

Cada serviço pode ser executado individualmente em modo dev do Quarkus:

```bash
cd election-management
./mvnw quarkus:dev
```

```bash
cd volting-app
./mvnw quarkus:dev
```

```bash
cd result-app
./mvnw quarkus:dev
```

> Em modo dev, o Quarkus utiliza **Dev Services** para subir automaticamente instâncias de MariaDB e Redis via Testcontainers, sem necessidade de configuração manual.

---

## Testes

```bash
# Executar testes unitários
./mvnw test

# Executar testes de integração
./mvnw verify -Dskip ITs=false
```

---

## CI/CD

### Build e versionamento de imagem

```bash
# Gera a imagem Docker do serviço e faz bump de versão no pom.xml
./cicd-build.sh election-management
```

### Deploy Blue/Green (zero downtime)

```bash
# Faz deploy da nova versão sem derrubar as instâncias antigas
# até que as novas estejam saudáveis
./cicd-blue-green-deployment.sh election-management 1.1.0
```

O script dobra o número de instâncias (green), aguarda o health check passar em todas, e então encerra as instâncias antigas (blue) com `SIGTERM`.

---

## Observabilidade

| Ferramenta   | Acesso                                    | Credenciais     |
|--------------|-------------------------------------------|-----------------|
| Traefik UI   | `http://localhost:8080`                   | —               |
| Graylog      | `http://logging.private.dio.localhost`    | `admin / admin` |
| Jaeger       | `http://telemetry.private.dio.localhost`  | `admin / admin` |

> Para configurar o input no Graylog: **System → Inputs → GELF UDP → porta 12201**

---

## Tecnologias utilizadas

- **Quarkus 3.8** — framework Java nativo para cloud
- **Java 17**
- **MariaDB 10.11** — persistência relacional (via Hibernate ORM + Flyway)
- **Redis 7** — cache e pub/sub para votos em tempo real
- **Traefik 2.9** — reverse proxy e load balancer
- **OpenTelemetry / Jaeger** — rastreamento distribuído
- **Graylog 5 / OpenSearch** — centralização de logs
- **Docker Compose** — orquestração local
