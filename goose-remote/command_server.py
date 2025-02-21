from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import subprocess
import uvicorn

app = FastAPI()

class Command(BaseModel):
    command: str

@app.post("/run")
async def run_command(command: Command):
    try:
        # Execute the goose command
        result = subprocess.run(
            ["goose", "run", "--text", command.command],
            capture_output=True,
            text=True,
            check=True
        )
        return {"output": result.stdout, "error": result.stderr}
    except subprocess.CalledProcessError as e:
        raise HTTPException(status_code=500, detail={
            "output": e.stdout,
            "error": e.stderr,
            "return_code": e.returncode
        })

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)