import json
from kafka import KafkaProducer

producer = KafkaProducer(
    bootstrap_servers=["localhost:9092"],
    value_serializer=lambda v: json.dumps(v).encode("utf-8"),
    acks=1,
    linger_ms=0,
    request_timeout_ms=10000,
    max_block_ms=10000,
    retries=0,
    api_version=(3, 7, 0),
)

metadata = producer.send("topic-audit-trail", {"test": "python"}).get(timeout=10)
print(f"sent to {metadata.topic} partition {metadata.partition} offset {metadata.offset}")

producer.flush()
producer.close()