/*
 * Copyright (C) 2024-2025 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.future0923.debug.tools.sql;

import io.github.future0923.debug.tools.base.hutool.sql.SqlCompressor;
import io.github.future0923.debug.tools.base.hutool.sql.SqlFormatter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * @author future0923
 */
class SqlCompressorTest {

    @Test
    void compressSqlRemoveBlockComments() {
        String sql = "SELECT * \n" +
                "FROM user  -- 用户表\n" +
                "WHERE name = '张 三'  \n" +
                "  AND age > 18 \n" +
                "  /* 这里是块注释，描述条件 */\n" +
                "  AND remark = \"备注 换行\"\n";

        String compressed = SqlCompressor.compressSql(sql, false);
        System.out.println(compressed);
        String expect =
                "SELECT * FROM user WHERE name = '张 三' AND age > 18 AND remark = \"备注 换行\"";
        Assertions.assertEquals(expect, compressed);
    }

    @Test
    void compressSqlPreserveBlockComments() {
        String sql = "SELECT * \n" +
                "FROM user  -- 用户表\n" +
                "WHERE name = '张 三'  \n" +
                "  AND age > 18 \n" +
                "  /* 这里是块注释，描述条件 */\n" +
                "  AND remark = \"备注 换行\"\n";

        String compressed = SqlCompressor.compressSql(sql, true);
        System.out.println(compressed);
        String expect =
                "SELECT * FROM user WHERE name = '张 三' AND age > 18 /* 这里是块注释，描述条件 */ AND remark = \"备注 换行\"";
        Assertions.assertEquals(expect, compressed);
    }

    @Test
    void formatSql() {
        String complexSQL = "UPDATE t_test\n" +
                "SET field_1 = '1',\n" +
                "    field_2 = 2,\n" +
                "    filed_3 = '{\"a\":\"'\"}',\n" +
                "    field_4 = 'good'\n" +
                "WHERE id=1723956044668174337";
        System.out.printf(SqlFormatter.format(complexSQL) + "\n");
        List<String> sqlTestCases = List.of(
                "SELECT name, age\n" +
                        "FROM employees e\n" +
                        "WHERE EXISTS (\n" +
                        "    SELECT 1\n" +
                        "    FROM departments d\n" +
                        "    WHERE d.id = e.department_id\n" +
                        "    AND d.name = 'Engineering'\n" +
                        ") \n" +
                        "AND age > 30\n" +
                        "ORDER BY age DESC;",

                "UPDATE orders\n" +
                        "SET status = 'Shipped',\n" +
                        "    shipping_date = (SELECT MAX(shipping_date) FROM shipment WHERE order_id = orders.id),\n" +
                        "    delivery_address = '1234 New Address, NY'\n" +
                        "WHERE id = 789456123;",

                "SELECT o.id, o.order_date, c.name AS customer_name, p.product_name\n" +
                        "FROM orders o\n" +
                        "INNER JOIN customers c ON c.id = o.customer_id\n" +
                        "LEFT JOIN products p ON p.id = o.product_id\n" +
                        "WHERE o.order_date > '2023-01-01'\n" +
                        "ORDER BY o.order_date DESC;",

                "SELECT name,\n" +
                        "       age,\n" +
                        "       CASE \n" +
                        "           WHEN age < 18 THEN 'Minor'\n" +
                        "           WHEN age BETWEEN 18 AND 65 THEN 'Adult'\n" +
                        "           ELSE 'Senior'\n" +
                        "       END AS age_group\n" +
                        "FROM users\n" +
                        "WHERE EXISTS (\n" +
                        "    SELECT 1\n" +
                        "    FROM transactions t\n" +
                        "    WHERE t.user_id = users.id\n" +
                        "    AND t.amount > 500\n" +
                        ");",

                "INSERT INTO products (name, price, stock)\n" +
                        "VALUES\n" +
                        "    ('Product A', 199.99, 100),\n" +
                        "    ('Product B', 299.99, 50),\n" +
                        "    ('Product C', 149.99, 200);",

                "SELECT * \n" +
                        "FROM sales\n" +
                        "WHERE region IN ('North', 'East', 'South')\n" +
                        "  AND sales_date BETWEEN '2023-01-01' AND '2023-12-31'\n" +
                        "  AND product_id NOT IN (\n" +
                        "      SELECT product_id\n" +
                        "      FROM discontinued_products\n" +
                        "      WHERE year_discontinued = 2022\n" +
                        "  )\n" +
                        "ORDER BY sales_date;",

                "DELETE FROM employees\n" +
                        "WHERE department_id = (\n" +
                        "    SELECT id \n" +
                        "    FROM departments \n" +
                        "    WHERE name = 'Accounting'\n" +
                        ")\n" +
                        "AND hire_date < '2015-01-01';",

                "INSERT INTO audit_logs (user_id, action, timestamp)\n" +
                        "SELECT u.id, 'Login', NOW()\n" +
                        "FROM users u\n" +
                        "WHERE u.last_login < (SELECT MAX(login_time) FROM user_sessions WHERE user_id = u.id)\n" +
                        "  AND u.status = 'active';",

                "SELECT department_id, COUNT(employee_id) AS num_employees\n" +
                        "FROM employees\n" +
                        "GROUP BY department_id\n" +
                        "HAVING COUNT(employee_id) > 10\n" +
                        "ORDER BY num_employees DESC;",

                "CREATE TABLE orders (\n" +
                        "    id INT PRIMARY KEY,\n" +
                        "    order_date DATE NOT NULL,\n" +
                        "    customer_id INT,\n" +
                        "    status VARCHAR(50),\n" +
                        "    total_amount DECIMAL(10, 2),\n" +
                        "    FOREIGN KEY (customer_id) REFERENCES customers(id)\n" +
                        "    CHECK (total_amount >= 0)\n" +
                        ");",

                "ALTER TABLE employees\n" +
                        "ADD COLUMN phone_number VARCHAR(15) NULL,\n" +
                        "MODIFY COLUMN status VARCHAR(20) NOT NULL,\n" +
                        "DROP COLUMN birthdate;",

                "SELECT product_name, price\n" +
                        "FROM products\n" +
                        "WHERE price > 1000\n" +
                        "UNION\n" +
                        "SELECT product_name, price\n" +
                        "FROM clearance_products\n" +
                        "WHERE price < 100\n" +
                        "ORDER BY price DESC;",

                "SELECT department_id, \n" +
                        "       COUNT(CASE WHEN status = 'active' THEN 1 END) AS active_employees,\n" +
                        "       SUM(CASE WHEN status = 'active' THEN salary ELSE 0 END) AS total_active_salary,\n" +
                        "       AVG(salary) AS avg_salary\n" +
                        "FROM employees\n" +
                        "GROUP BY department_id\n" +
                        "HAVING COUNT(id) > 5;",

                "UPDATE users\n" +
                        "SET profile_data = JSON_SET(profile_data, '$.address.city', 'New York')\n" +
                        "WHERE id = 12345;",

                "WITH department_sales AS (\n" +
                        "    SELECT department_id, SUM(amount) AS total_sales\n" +
                        "    FROM sales\n" +
                        "    GROUP BY department_id\n" +
                        ")\n" +
                        "SELECT e.name, d.total_sales\n" +
                        "FROM employees e\n" +
                        "JOIN department_sales d ON e.department_id = d.department_id\n" +
                        "WHERE d.total_sales > 10000\n" +
                        "ORDER BY d.total_sales DESC;",

                "SELECT a.order_id, SUM(b.amount) AS total_amount\n" +
                        "FROM orders a\n" +
                        "JOIN order_items b ON a.order_id = b.order_id\n" +
                        "GROUP BY a.order_id\n" +
                        "HAVING total_amount > 1000\n" +
                        "ORDER BY total_amount DESC;",

                "SELECT first_name, last_name\n" +
                        "FROM employees\n" +
                        "WHERE first_name LIKE 'J%' \n" +
                        "  AND last_name LIKE '%son'\n" +
                        "  AND hire_date BETWEEN '2022-01-01' AND '2023-01-01'\n" +
                        "ORDER BY last_name;",

                "SELECT DISTINCT e.name, p.product_name\n" +
                        "FROM employees e\n" +
                        "JOIN product_sales ps ON e.id = ps.employee_id\n" +
                        "JOIN products p ON ps.product_id = p.id\n" +
                        "WHERE e.department_id = 2;",

                "SELECT product_name\n" +
                        "FROM products\n" +
                        "WHERE price > 100\n" +
                        "EXCEPT\n" +
                        "SELECT product_name\n" +
                        "FROM clearance_products\n" +
                        "WHERE price < 50;"
        );
        sqlTestCases.forEach(item -> System.out.println("============\n" + SqlFormatter.format(item)));
    }

}