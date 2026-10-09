# README screenshots

`capture.py` seeds demo data (folder `batch`, run requests, grants, a configuration change) and writes the five
images in `docs/images/` that the README uses. Run it against a fresh e2e stack after UI changes (Python >= 3.10):

```bash
mvn -ntp clean package -DskipTests && cd e2e && cp -n .env.example .env    # then set the passwords in .env
scripts/reset.sh; TZ=UTC scripts/up.sh                                      # fresh JENKINS_HOME, UTC timestamps
python3 -m venv ~/.venvs/bc && ~/.venvs/bc/bin/pip install playwright requests pillow && ~/.venvs/bc/bin/playwright install chromium
~/.venvs/bc/bin/python readme-screenshots/capture.py && scripts/down.sh      # review the PNGs before committing
```
