# SCD-сценарии

Ниже команды для ручной проверки. Эти сценарии также проверяет `python3 scripts/e2e.py` после свежего setup; скрипт изменяет только синтетические учебные записи. Штатный автогрейдер курса запускается отдельно. Примеры команд используют Docker; с Podman замените движок на выбранное подключение. Для SQL можно использовать:

```sh
docker compose --env-file .env.local exec clickhouse sh -c 'clickhouse-client --port "$CLICKHOUSE_PORT" --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --database "$CLICKHOUSE_DATABASE"'
```

### ФИО и телефон

```sh
curl -i -X PATCH localhost:8080/users/1 -H 'Content-Type: application/json' -d '{"fio":"Новое имя"}'
curl -i -X PATCH localhost:8080/users/1 -H 'Content-Type: application/json' -d '{"phone":"+79111111111"}'
curl -i -X PATCH localhost:8080/users/1 -H 'Content-Type: application/json' -d '{"phone":"+79222222222"}'
curl localhost:8080/users/1
```

```sql
SELECT fio, phone, phone_prev FROM dim_users FINAL WHERE id = 1;
```

Ожидаются новое ФИО, последний и предпоследний телефон. ФИО SCD1 исчезает из текущего логического состояния; старые физические строки могут существовать до фонового слияния. Требование физического стирания нельзя доказать одним FINAL. Повторная передача того же телефона не меняет phone_prev.

### Тариф и исторический счёт

```sh
curl 'localhost:8080/tariffs?date=2025-10-31'
curl 'localhost:8080/tariffs?date=2025-11-01'
```

```sql
SELECT id, price_day_kwph, valid_from, valid_to FROM dim_tariffs FINAL ORDER BY id, valid_from;
SELECT created_at, day_kw, night_kw, price FROM fact_invoice WHERE realty_id = 1 ORDER BY created_at;
```

На границе возвращаются разные интервалы. Для старого счета проверьте `price = day_kw * 500 + night_kw * 250`, для нового — `day_kw * 620 + night_kw * 310`. Использование текущей цены не изменяет сохранённую историческую сумму.

### Корректировка показания

```sh
curl -i -X POST localhost:8080/realty/1/readings -H 'Content-Type: application/json' -d '{"user_id":1,"gauge_id":1,"tariff_id":1,"reading_date":"2026-10-01","day_kw":1250,"night_kw":300}'
curl 'localhost:8080/realty/1/readings?date_from=2026-10-01&date_to=2026-10-01'
```

```sql
SELECT * FROM fact_meter_readings FINAL WHERE realty_id = 1 AND reading_date = '2026-10-01';
```

Ожидается одна актуальная строка с новыми значениями. До слияния исходная и корректирующая физические строки могут существовать одновременно.

### Замена счётчика

```sh
curl -i -X POST localhost:8080/realty/1/gauges -H 'Content-Type: application/json' -d '{"gauge_id":2}'
```

```sql
SELECT * FROM fact_gauge_bindings FINAL WHERE realty_id = 1;
```

Ожидается только новая активная установка. Отмена копирует прежний ключ, версию и created_at с противоположным sign; новая запись получает version + 1. Если активной установки не было, новая версия равна 1.

### Удаление ошибочной строки

На отдельной учебной базе после проверки исходных объёмов:

```sql
INSERT INTO fact_invoice (realty_id,user_id,tariff_id,gauge_id,day_kw,night_kw,price,deadline,created_at)
VALUES (999999999,1,1,1,10,1,9999990,'2026-11-10','2026-10-31 12:00:00');
SELECT count() FROM fact_invoice WHERE realty_id = 999999999;
ALTER TABLE fact_invoice DELETE WHERE realty_id = 999999999 SETTINGS mutations_sync = 1;
SELECT count() FROM fact_invoice WHERE realty_id = 999999999;
```

Ожидается 1 до удаления и 0 после синхронной мутации. Частые изменения API используют версии, а не такие мутации.
