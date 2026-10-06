"""测试公共 sys.path 引导（eval/ragas 脚本目录加入 import 路径）。"""
import sys
from pathlib import Path

RAGAS_DIR = Path(__file__).resolve().parents[1]
if str(RAGAS_DIR) not in sys.path:
    sys.path.insert(0, str(RAGAS_DIR))
