using System.Collections;
using System.Collections.Generic;
using System.Threading.Tasks;

namespace EmpowerOps.Volition.RefClient
{
    public interface IEvaluator
    {
        Task<EvaluationResult> EvaluateAsync(IDictionary<string, double> inputs, IList outputs);
        void Cancel();
        void SetFailNext();
    }

}
