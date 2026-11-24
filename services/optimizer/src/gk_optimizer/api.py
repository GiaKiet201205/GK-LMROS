from typing import Any
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from gk_optimizer import solver

app = FastAPI(title='GK Dynamic Pickup & Delivery', version='2.0')

class SolveRequest(BaseModel):
    warehouses: list[dict[str, Any]]
    vehicles: list[dict[str, Any]]
    routes: list[dict[str, Any]] = Field(default_factory=list)
    orders: list[dict[str, Any]] = Field(default_factory=list)
    now_min: float
    seconds: float = Field(default=3, ge=.05, le=10)
    max_stops: int = Field(default=101, ge=2, le=101)
    max_orders: int = Field(default=45, ge=1, le=45)
    group_deliveries: bool = False
    fixed_trip_cost: float = Field(default=35000, ge=0)

@app.get('/health')
def health():
    return {'status':'ok'}

@app.post('/solve')
def solve(req: SolveRequest):
    try:
        return solver.solve(req.model_dump())
    except ValueError as error:
        raise HTTPException(status_code=503, detail=str(error)) from None
    except Exception:
        raise HTTPException(status_code=503, detail='Routing provider is unavailable; the previous route was preserved') from None
