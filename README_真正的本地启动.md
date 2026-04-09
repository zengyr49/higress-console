```shell
cd backend/console/target
  java \
    -Dhigress-console.kube-config=/Users/zengyr7/Downloads/cls-en1 \
    -Dhigress-console.ns=higress-for-wgb-uat \
    -Dhigress-console.controller.watched-namespace=higress-for-wgb-uat \
    -Dhigress-console.controller.service.host=higress-controller.higress-for-wgb-uat.svc.cluster.local \
    -Dhigress-console.controller.service.port=15014 \
    -jar higress-console.jar
```
以上也可以放到application properties里面