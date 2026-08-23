# TRACK_T4_NOTES — Binding plane + persistence

Sandbox: `/tmp/opencode/dra-t4` · JDK zulu-25 · JUnit 5 · không git commit.

## Kết quả build

- `mvn -q -pl dra-core test` → **45 tests xanh** (bind suite 5 cũ + mở rộng).
- `mvn -q -pl dra-app -am test` → **11 tests xanh** (persist).
- Stress: WriteBehindPersistenceTest + PgBindingDaoH2Test chạy lặp 15×/8× liên tiếp,
  0 flake sau khi sửa race (chi tiết §5).

## 1. Rà soát schema (V1__dra_baseline.sql)

**Kết luận: KHÔNG cần migration V2.** V1 đủ 8 cột khớp `BindingEntry`
(key, group_id, peer_id, origin_host, origin_realm, ingress_peer_id, created_at,
expires_at) + index expires_at cho sweep/purge. Lệch thật nằm ở lớp ORM/DAO:

1. `DraBindingEntity` trước đây không có `@Column` tường minh → Hibernate implicit
   đặt cột `bindingKey` ≠ `key` của V1 (prod PG sẽ lệch schema khi flyway bật).
   Đã map tường minh toàn bộ field snake_case; `bindingKey` → `@Column(name = "\"key\"")`.
   Quote là bắt buộc vì **KEY là từ khóa reserved trong H2 kể cả MODE=PostgreSQL**
   (%test drop-and-create sẽ chết khi boot nếu không quote); trên PG `"key"` quoted
   lowercase trùng đúng cột unquoted `key` của V1 → zero-diff prod.
2. `AuditLogEntity.diffJson` → `diff_json`, `RouteConfigEntity.createdAt` đã đúng
   `created_at`. Kiểm chứng tự động bởi `EntitySchemaAlignmentTest`: set cột entity
   (reflection) == set cột parse từ V1 cho cả 3 bảng.
3. `PgBindingDao`: phát hiện quan trọng — **H2 MODE=PostgreSQL KHÔNG hỗ trợ
   `ON CONFLICT DO UPDATE`** (mọi variant đều syntax error, chỉ `DO NOTHING` được).
   DAO giờ chọn câu upsert theo dialect lúc khởi tạo (`DatabaseMetaData`):
   PG giữ nguyên `ON CONFLICT ("key") DO UPDATE SET ... EXCLUDED.*`;
   H2 dùng `MERGE INTO ... KEY("key") VALUES(...)`. Delete-by-array `= ANY(?)`
   chạy tốt trên cả hai. Toàn bộ SQL parameterized — injection-safe.
4. Precision: TIMESTAMPTZ/H2 TIMESTAMP giữ **microseconds** (JDBC round half-up),
   nano trong `BindingEntry.createdAt/expiresAt` bị cắt khi persist — vô hại với
   TTL scale giờ/ngày, đã test tường minh.
5. Sửa test cũ có sẵn đang đỏ từ baseline: `MigrationSqlSanityTest` so-khớp
   whitespace cứng + đếm 6 statement trong khi V1 có 5 (3 bảng + 2 index).
   Test thuộc persist (T4) nên chuẩn hóa theo V1 thật; V1 không bị đụng.

## 2. TTL sweep glue (dra-app.persist)

- `SweepSource { List<BindingEntry> sweepExpired(Instant now, int limit) }` — interface
  mới trong `app.persist`, tách job khỏi `InMemoryBindingStore` cụ thể.
- `BindingSweepJob` (`@ApplicationScoped` + `@Scheduled`):
  - `every = "{dra.bindings.sweep-every-seconds}s"` (default property =60 trong
    application.properties). LƯU Ý bootstrap: giá trị phải parse được thành Duration;
    số trơn "60" theo quy tắc MP Config Duration = giây, nhưng nếu Quarkus bản của
    bạn từ chối thì đổi property thành `60s`.
  - Mỗi tick: gọi `SweepSource.sweepExpired(now, batch)` cho từng source
    (`dra.bindings.sweep-batch-size`, default 1000) → keys removed đi thẳng
    `PersistenceHook.removeBatch()` (xóa PG batch, vẫn off request-path vì nằm
    trên scheduler thread) → `BindingMetricsBridge.recordSweep(n)`.
  - Gauge `BINDING_SIZE` (`dra_binding_size`) đọc live `store.size()` qua bridge
    tại thời điểm scrape — sau sweep tự phản ánh đúng, không cần push riêng.
- Injection dùng `Instance<>` cho SweepSource/PersistenceHook/Bridge: **app vẫn boot
  khi chưa có bean nào** (job no-op). Bootstrap cần cung cấp:
  1. Bean `SweepSource` wrap `InMemoryBindingStore` (hoặc ClusteredBindingStore
     nếu expose chung interface — khuyến nghị thêm `sweepExpired` default-method
     ở adapter, KHÔNG sửa frozen contracts);
  2. Bean `PersistenceHook` = `new PgBindingDao(dataSource)` (hoặc chain
     WriteBehindPersistence bọc PgBindingDao — xem §4);
  3. Bean `BindingMetricsBridge` nếu muốn metric sweep.

## 3. ServerInitiatedResolverImpl

- Thêm nhánh thứ ba: IMSI → MSISDN → `FRAMED_IP_APN` (PCC binding TS 29.213 §7.3.2:
  match user identity / IP / APN). Key format `<TYPE>:<value>` nhất quán BindingKeys.
- **DEVIATION có chủ đích so với mission text**: `preferredPeerId` =
  `entry.ingressPeerId()`, KHÔNG phải `entry.peerId()`. peerId của binding là HSS
  được chọn lúc ULR (§4.1), route ngược IDR/CLR/RAR về MME phải theo link ingress
  (architecture §4.2: `BindingStore[IMSI]→{ingressPeerId=mme-01-link}`); dùng peerId
  sẽ route ngược vào HSS pool. Existing tests cũng khóa semantic này từ trước.
- Không binding → empty bất kể Dest-Host (caller fail-closed 3002; khi Dest-Host
  rõ thì caller route theo host — branch đã test tường minh).
- **TTL refresh-on-hit CHƯA có trong resolver/get** (theo chỉ thị: get đã đọc LRU,
  không nhét side-effect). Caller refresh bằng `store.put(entry.withTtl(now+ttl))`
  sau khi resolve hit; pattern đã có test `refreshedTtlKeepsEntryAlive`.

## 4. WriteBehindPersistence

- Verify xong các yêu cầu: drain async thread riêng (virtual thread
  `dra-binding-write-behind`), flush-on-close, coalesce same-key last-writer-wins,
  drop-oldest backpressure. Bổ sung:
  - **Retry đúng 1 lần**: hook fail lần đầu → batch re-stage (giữ seq gốc), lần
    flush kế áp lại; fail lần 2 → drop hẳn (`droppedAfterRetryCount`). Counters:
    `failedBatchCount`, `retriedBatchCount`, `droppedAfterRetryCount`.
  - Metric **pending-size**: `pendingCount()` = queue + staging + retryStage.
- **Fix race thật** (bộc lộ khi stress): worker cũ poll op ra khỏi queue rồi mới
  stage — cửa sổ in-flight làm `flush()` chụp snapshot thiếu op (op kẹt đến chu kỳ
  60s sau) và op stale có thể đè op mới. Fix: chuyển giao queue→staging nguyên tử
  trong lock (poll+merge cùng synchronized), cộng **sequence number cấp lúc
  enqueue** — merge luôn giữ seq cao hơn ⇒ last-writer-wins tuyệt đối, retry giữ
  seq gốc nên không bao giờ đè dữ liệu mới hơn. Worker không còn block trên lock
  khi chờ queue (sleep 2ms ngoài lock) ⇒ submit() không thể bị chặn >µs.
- Wiring gợi ý cho bootstrap: `WriteBehindPersistence(pgBindingDao)` làm
  PersistenceHook đưa vào binding path; `batch-size=200`, `interval-millis=500`
  đã có sẵn property (`dra.bindings.writebehind.*`) — bootstrap đọc và truyền vào
  ctor `(hook, intervalMillis, maxBuffered)`; maxBuffered chưa có property riêng
  (dùng default 100_000) — cần thì bổ sung sau, tôi để nguyên để không thêm knob
  không ai đọc.

## 5. ClusteredBindingStore

- Replication hook giờ **fire-and-forget thật**: exception từ hook nuốt +
  đếm `replicationFailureCount()`, không lan ra caller, local write không mất.
  Counters expose: replicatedPutCount/RemoveCount/FailureCount.
- Ctor mới nhận `Executor` để dispatch replication ngoài thread gọi put()
  (test chứng minh put trả lời trước khi hook chạy). Default vẫn synchronous
  inline (noop hook) cho deterministic.
- Test consistency: 2 store instances + 1 bus giả fanout idempotent
  (onPut→put cả 2 node, onRemove→remove cả 2 node) — put/remove lan truyền
  2 chiều đúng.
- **Cluster note (kế thừa)**: Infinispan `DIST_SYNC` cache-name `dra-cluster` +
  endpoint lease của ra-diameter `initHa()` — hiện tại chỉ tới mức hook interface
  `ReplicationHook`; chưa có impl ISPN thật (cần cache manager inject từ
  microjainslee cluster layer — việc của bootstrap/integrator phase sau). Bus giả
  trong test chính là contract mà ISPN impl phải thỏa: onPut/onRemove idempotent,
  fanout đến mọi node, không re-entry loop.

## 6. application.properties (T4 sở hữu từ đây)

Thêm/giữ: `%prod.quarkus.flyway.migrate-at-start=true` (PG SoT migrate khi boot);
`%test` giữ H2 drop-and-create + flyway off như cũ; `dra.bindings.ttl-default-seconds=86400`,
`dra.bindings.sweep-every-seconds=60`, `dra.bindings.sweep-batch-size=1000`,
`dra.bindings.writebehind.batch-size=200`, `dra.bindings.writebehind.interval-millis=500`.
Không đụng property track khác (`dra.tx.timeout-millis` giữ nguyên).

## 7. Danh sách file

dra-core main: `ServerInitiatedResolverImpl` (+FRAMED_IP_APN), `ClusteredBindingStore`
(fire-and-forget + Executor + counters), `WriteBehindPersistence` (retry-once, seq
merge, pendingCount, atomic drain), `PgBindingDao` (dialect upsert, quoted "key"),
`pom.xml` (+com.h2database:h2 test scope).
dra-core test: `ServerInitiatedResolverImplTest` +4, `WriteBehindPersistenceTest`
(+4 mới, 1 sửa kỳ vọng retry), `ClusteredBindingStoreTest` +3, `PgBindingDaoH2Test` (mới, 5 test).
dra-app main: `DraBindingEntity`/`AuditLogEntity`/`RouteConfigEntity` (@Column tường minh),
`SweepSource` (mới), `BindingSweepJob` (mới), `application.properties`.
dra-app test: `MigrationSqlSanityTest` (sửa khớp V1), `EntitySchemaAlignmentTest` (mới, 4),
`BindingSweepJobTest` (mới, 3).

Frozen contracts không bị đụng (đọc thôi): BindingEntry/BindingStore/BindingKeys/
ServerInitiatedResolver/PeerRouteTarget/wire/peer/TxState+TxTable/lb/engine/metrics/common.
