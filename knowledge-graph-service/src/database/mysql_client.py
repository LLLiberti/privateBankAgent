from contextlib import contextmanager
from typing import TYPE_CHECKING, Any, Generator

from src.utils.config import settings

if TYPE_CHECKING:
    from mysql.connector import MySQLConnection
else:
    MySQLConnection = Any


@contextmanager
def get_mysql_connection() -> Generator[MySQLConnection, None, None]:
    # Load the optional driver only when a real MySQL connection is requested.
    import mysql.connector

    connection = mysql.connector.connect(
        host=settings.mysql_host,
        port=settings.mysql_port,
        user=settings.mysql_user,
        password=settings.mysql_password,
        database=settings.mysql_database,
        charset="utf8mb4",
        use_unicode=True,
        connection_timeout=10,
    )

    try:
        yield connection
    finally:
        if connection.is_connected():
            connection.close()
