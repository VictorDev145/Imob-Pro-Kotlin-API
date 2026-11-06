# Migração controlada do SQLite legado para PostgreSQL

## Estado
A ferramenta `scripts/legacy-migration/export_sqlite.py` faz snapshot somente leitura, valida `integrity_check` e `foreign_key_check`, exporta 19 tabelas em JSONL, gera SHA-256 por arquivo e pode copiar a pasta `uploads` com hashes. Ela **não importa dados** no PostgreSQL e não altera o banco original.

## Procedimento de exportação

Faça primeiro uma cópia consistente do arquivo SQLite e mantenha a aplicação antiga em modo somente leitura durante o corte:

```bash
python scripts/legacy-migration/export_sqlite.py \
  --database /caminho/seguro/imobpro.sqlite \
  --uploads /caminho/seguro/uploads \
  --output /caminho/seguro/imob-pro-export
```

Se não houver pasta de uploads, omita `--uploads`. O diretório de saída deve estar vazio. No Windows, restrinja as permissões NTFS da pasta exportada ao operador responsável. O export contém dados pessoais e hashes de senha.

## Tabelas e política

| SQLite legado | Tratamento no destino |
|---|---|
| `users` | Criar identidade global por e-mail normalizado; preservar hash bcrypt somente após validar o formato e nunca exportar senha em claro |
| `properties` | Criar imóvel sob uma organização de migração; mapear `owner_id` para membership ativo; manter campos não suportados no relatório de reconciliação |
| `property_images` | Copiar arquivos com checksum; criar metadados de mídia vinculados ao imóvel e à organização |
| `leads` | Mapear customer, assigned_to e property; normalizar status e source; rejeitar ou colocar em quarentena referências órfãs |
| `visits`, `tasks` | Mapear todos os relacionamentos; validar que cada referência pertença à mesma organização |
| `favorites`, `saved_searches`, `collections`, `collection_items` | Preservar ownership do usuário e garantir que itens só apontem para anúncios que podem ser exibidos |
| `notifications`, `messages` | Preservar destinatário/remetente; mensagens só são importadas se todos os participantes puderem ser associados a memberships válidos |
| `activity_log`, `property_views` | Importar somente campos minimizados e permitidos; não migrar session keys em texto puro |
| `settings`, `site_pages` | Migrar como configurações/páginas da organização; validar slug e conteúdo público |
| `privacy_requests`, `automation_rules` | Migrar com revisão manual de status, escopo e catálogo de ações |
| `sessions` | Excluída: todas as sessões antigas são invalidadas no corte |
| `password_reset_tokens` | Excluída: tokens antigos são invalidados; titulares solicitam novos links |
| `saved_search_alerts` | Recriar associação após a migração de buscas; não copiar IDs numéricos como UUID |

## Modelo de tenant no corte inicial

A instalação legada é um único portal. O corte inicial deve criar **uma organização de legado** e vincular todos os usuários importados como memberships. Os papéis legados não podem ser promovidos automaticamente a OWNER/ADMIN sem revisão:

- `admin` → ADMIN somente após validação explícita do responsável pelo negócio.
- `manager` → MANAGER após revisão.
- `editor` → papel limitado equivalente; nunca assumir privilégio de gestão total.
- `agent` → AGENT.
- `owner` → OWNER somente para o anunciante que realmente controla seus imóveis.
- `customer` → CUSTOMER.

A organização deve ter um responsável primário confirmado antes de aceitar tráfego. Nenhum `organization_id` enviado pelo navegador participa da decisão.

## Mapeamento de identificadores

IDs SQLite são inteiros e não podem ser convertidos diretamente em UUIDs. A etapa de importação deve manter uma tabela de correspondência por entidade, por exemplo `legacy_id_map(entity_type, legacy_id, new_id)`, com unicidade em `(entity_type, legacy_id)`. Criar entidades-pai antes das filhas e resolver todas as FKs por essa tabela.

Não descarte linhas órfãs silenciosamente. Gere um CSV/JSON de quarentena com tabela, ID legado, FK ausente e motivo. A reconciliação precisa comparar contagens de origem, importadas e rejeitadas por tabela.

## Cutover e rollback

1. Fazer backup SQLite e export com hashes; validar integridade.
2. Congelar escritas no Express e gerar export final.
3. Importar em PostgreSQL de staging; não apontar a interface para esse banco ainda.
4. Validar contagens, FKs, totais financeiros, imagens, usuários e amostras por entidade.
5. Executar testes de isolamento entre tenants e testes de contrato.
6. Fazer ensaio de rollback: reabrir o Express usando o banco original sem alterações.
7. Somente após aceite formal, mudar o tráfego para a API nova.
8. Manter o SQLite original, export e cópias de uploads imutáveis durante a janela de rollback definida pela operação.

## Bloqueio conhecido
O importador PostgreSQL ainda não foi ativado. É intencional não inventar conversões para campos e permissões que precisam de validação de negócio. A exportação auditável é a primeira etapa segura; o carregamento depende de mapear as migrations de destino, validar os campos legados completos e definir a política de papéis da organização de migração.


### Windows PowerShell

No Windows, execute na pasta do repositório:

```powershell
py -3 .\scripts\legacy-migration\export_sqlite.py `
  --database "C:\Projetos\Imob-Pro\imobpro.sqlite" `
  --uploads "C:\Projetos\Imob-Pro\uploads" `
  --output "D:\Backups\imob-pro-export"
```

Ajuste os caminhos para o local real do arquivo SQLite e da pasta de imagens. Feche o servidor legado antes de fazer a cópia de origem ou use uma cópia consistente com seus arquivos WAL. Não envie o export para o GitHub: ele contém dados pessoais e hashes de senha.


## Importação verificável para staging PostgreSQL

Depois de gerar e revisar o export, aplique as migrations da API em um banco PostgreSQL de staging (a V11 cria `legacy_import_batches` e `legacy_import_rows`). Instale a dependência e execute:

```powershell
py -3 -m pip install -r .\scripts\legacy-migration\requirements.txt
py -3 .\scripts\legacy-migration\import_to_postgres.py `
  --export "D:\Backups\imob-pro-export" `
  --dsn "postgresql://imobpro:SEGREDO@localhost:5432/imobpro"
```

O importador valida SHA-256 e contagens antes de abrir a transação, importa cada linha como JSONB em staging, usa a chave primária real (incluindo chaves compostas) ou uma chave determinística para tabelas sem PK, compara hashes em reexecuções e grava o lote como `RECONCILED`. A mesma exportação gera o mesmo ID de lote, tornando a operação idempotente.

**Importante:** esse processo preserva e reconcilia os registros originais em staging, mas não converte automaticamente os registros para `users`, `properties`, `leads` e demais tabelas de domínio. A conversão de domínio exige um mapeamento validado de papéis, ownership, relacionamentos e campos específicos. Não a execute em produção até concluir essa etapa, validar as contagens e ensaiar rollback.
