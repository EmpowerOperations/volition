using System.Collections;
using Google.Protobuf.Collections;

namespace EmpowerOps.Volition.RefClient
{
    public interface IEvaluator
    {
        EvaluationResult Evaluate(MapField<string, double> inputs, IList outputs);
        void Cancel();
        void SetFailNext();
    }

}
