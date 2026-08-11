from src.database.mysql_client import get_mysql_connection


def test_mysql() -> None:
    with get_mysql_connection() as connection:
        cursor = connection.cursor()

        try:
            cursor.execute(
                """
                SELECT VERSION(), DATABASE(), CURRENT_USER()
                """
            )

            version, database, current_user = cursor.fetchone()

            print("MySQL连接成功")
            print("MySQL版本：", version)
            print("当前数据库：", database)
            print("当前用户：", current_user)

        finally:
            cursor.close()


if __name__ == "__main__":
    test_mysql()