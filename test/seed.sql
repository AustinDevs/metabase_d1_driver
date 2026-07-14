CREATE TABLE customers (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  email TEXT,
  is_active BOOLEAN,
  created_at DATETIME
);

CREATE TABLE orders (
  id INTEGER PRIMARY KEY,
  customer_id INTEGER NOT NULL REFERENCES customers(id),
  total REAL,
  status TEXT,
  ordered_at DATETIME
);

INSERT INTO customers (id, name, email, is_active, created_at) VALUES
  (1, 'Ada Lovelace',   'ada@example.com',   1, '2025-11-03 09:15:00'),
  (2, 'Alan Turing',    'alan@example.com',  1, '2025-12-18 14:30:00'),
  (3, 'Grace Hopper',   'grace@example.com', 0, '2026-01-22 11:05:00'),
  (4, 'Edsger Dijkstra','edsger@example.com',1, '2026-02-14 16:45:00');

INSERT INTO orders (id, customer_id, total, status, ordered_at) VALUES
  (1, 1,  49.99, 'shipped',   '2026-01-05 10:00:00'),
  (2, 1, 120.50, 'shipped',   '2026-02-11 12:30:00'),
  (3, 2,  15.00, 'pending',   '2026-02-20 09:45:00'),
  (4, 3, 220.00, 'shipped',   '2026-03-02 17:20:00'),
  (5, 3,  35.75, 'cancelled', '2026-03-15 08:10:00'),
  (6, 4,  99.99, 'shipped',   '2026-04-01 13:55:00'),
  (7, 2,  75.25, 'pending',   '2026-05-09 15:40:00'),
  (8, 1,  10.00, 'shipped',   '2026-06-21 19:05:00');
