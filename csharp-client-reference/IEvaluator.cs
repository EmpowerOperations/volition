using System.Collections;
using System.Collections.Generic;

namespace EmpowerOps.Volition.RefClient
{
    public interface IEvaluator
    {
        EvaluationResult Evaluate(IDictionary<string, double> inputs, IList outputs);
        void Cancel();
        void SetFailNext();
    }

}
