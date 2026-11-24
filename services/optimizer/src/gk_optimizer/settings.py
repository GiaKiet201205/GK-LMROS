"""Configuration shared by the HTTP boundary and the standalone solver."""
import math
import os
from dataclasses import dataclass


def default_fixed_trip_cost() -> float:
    try:
        value = float(os.getenv('OPTIMIZER_FIXED_TRIP_COST', '35000'))
    except ValueError:
        raise ValueError('OPTIMIZER_FIXED_TRIP_COST must be a finite amount between 0 and 1000000000') from None
    if not math.isfinite(value) or not 0 <= value <= 1_000_000_000:
        raise ValueError('OPTIMIZER_FIXED_TRIP_COST must be a finite amount between 0 and 1000000000')
    return value


@dataclass(frozen=True)
class Settings:
    internal_token: bytes
    fixed_trip_cost: float

    @classmethod
    def from_env(cls):
        token = os.getenv('INTERNAL_SERVICE_TOKEN', '')
        if not token.strip() or token.startswith('replace-with-'):
            raise ValueError('INTERNAL_SERVICE_TOKEN must be explicitly configured')
        return cls(token.encode('utf-8'), default_fixed_trip_cost())
