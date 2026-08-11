import os
from pathlib import Path

from dotenv import load_dotenv


# 项目根目录：
# D:\工行软开\icbc-private-bank
PROJECT_ROOT = Path(__file__).resolve().parents[2]

# .env 文件路径
ENV_PATH = PROJECT_ROOT / ".env"

# 加载 .env
load_dotenv(ENV_PATH)


class Settings:
    """项目统一配置。"""

    # MySQL配置
    mysql_host = os.getenv("MYSQL_HOST", "")
    mysql_port = int(os.getenv("MYSQL_PORT", "3306"))
    mysql_user = os.getenv("MYSQL_USER", "")
    mysql_password = os.getenv("MYSQL_PASSWORD", "")
    mysql_database = os.getenv("MYSQL_DATABASE", "")

    # Neo4j配置
    neo4j_uri = os.getenv(
        "NEO4J_URI",
        "neo4j://localhost:7687",
    )
    # Keep NEO4J_USER as a backwards-compatible fallback for older .env files.
    neo4j_username = os.getenv(
        "NEO4J_USERNAME",
        os.getenv("NEO4J_USER", "neo4j"),
    )
    neo4j_user = neo4j_username
    neo4j_password = os.getenv("NEO4J_PASSWORD", "")
    neo4j_database = os.getenv("NEO4J_DATABASE", "neo4j")
    neo4j_connect_timeout_seconds = float(
        os.getenv("NEO4J_CONNECT_TIMEOUT_SECONDS", "10")
    )
    neo4j_max_retries = int(os.getenv("NEO4J_MAX_RETRIES", "2"))

    # 百炼配置
    dashscope_api_key = os.getenv("DASHSCOPE_API_KEY", "")
    bailian_model = os.getenv("BAILIAN_MODEL", "qwen-plus")
    bailian_base_url = os.getenv(
        "BAILIAN_BASE_URL",
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
    )
    bailian_endpoint = os.getenv("BAILIAN_ENDPOINT", bailian_base_url)
    bailian_timeout_seconds = float(os.getenv("BAILIAN_TIMEOUT_SECONDS", "60"))
    bailian_max_retries = int(os.getenv("BAILIAN_MAX_RETRIES", "2"))

    @classmethod
    def validate_bailian(cls) -> None:
        """检查百炼必要配置，不返回或记录完整密钥。"""

        missing = []
        if not cls.dashscope_api_key:
            missing.append("DASHSCOPE_API_KEY")
        if not cls.bailian_model:
            missing.append("BAILIAN_MODEL")
        if not cls.bailian_endpoint:
            missing.append("BAILIAN_ENDPOINT/BAILIAN_BASE_URL")
        if missing:
            raise RuntimeError("缺少百炼环境变量：" + ", ".join(missing))

    @classmethod
    def validate_mysql(cls) -> None:
        """检查MySQL必要配置是否填写。"""

        required = {
            "MYSQL_HOST": cls.mysql_host,
            "MYSQL_USER": cls.mysql_user,
            "MYSQL_PASSWORD": cls.mysql_password,
            "MYSQL_DATABASE": cls.mysql_database,
        }

        missing = [
            name
            for name, value in required.items()
            if not value
        ]

        if missing:
            raise RuntimeError(
                "缺少MySQL环境变量：" + ", ".join(missing)
            )

    @classmethod
    def validate_neo4j(cls) -> None:
        """检查 Neo4j 配置，不返回或记录完整密码。"""

        required = {
            "NEO4J_URI": cls.neo4j_uri,
            "NEO4J_USERNAME": cls.neo4j_username,
            "NEO4J_PASSWORD": cls.neo4j_password,
            "NEO4J_DATABASE": cls.neo4j_database,
        }
        missing = [name for name, value in required.items() if not value]
        if missing:
            raise RuntimeError("缺少 Neo4j 环境变量：" + ", ".join(missing))
        if cls.neo4j_connect_timeout_seconds <= 0:
            raise RuntimeError("NEO4J_CONNECT_TIMEOUT_SECONDS 必须大于 0")
        if cls.neo4j_max_retries < 0:
            raise RuntimeError("NEO4J_MAX_RETRIES 不能小于 0")


settings = Settings()
